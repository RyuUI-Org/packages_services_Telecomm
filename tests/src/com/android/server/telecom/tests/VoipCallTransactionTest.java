/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.OutcomeReceiver;
import android.telecom.CallException;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.TelecomSystem;
import com.android.server.telecom.callsequencing.voip.ParallelTransaction;
import com.android.server.telecom.callsequencing.voip.SerialTransaction;
import com.android.server.telecom.callsequencing.TransactionManager;
import com.android.server.telecom.callsequencing.CallTransaction;
import com.android.server.telecom.callsequencing.CallTransactionResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(JUnit4.class)
public class VoipCallTransactionTest extends TelecomTestCase {
    private StringBuilder mLog;
    private TransactionManager mTransactionManager;
    private static final TelecomSystem.SyncRoot mLock = new TelecomSystem.SyncRoot() { };

    private class TestVoipCallTransaction extends CallTransaction {
        public static final int SUCCESS = 0;
        public static final int FAILED = 1;
        public static final int TIMEOUT = 2;
        public static final int EXCEPTION = 3;

        private long mSleepTime;
        private String mName;
        private int mType;
        public boolean isFinished = false;

        public TestVoipCallTransaction(String name, long sleepTime, int type) {
            super(VoipCallTransactionTest.this.mLock);
            mName = name;
            mSleepTime = sleepTime;
            mType = type;
        }

        @Override
        public CompletionStage<CallTransactionResult> processTransaction(Void v) {
            if (mType == EXCEPTION) {
                mLog.append(mName).append(" exception;\n");
                throw new IllegalStateException("TEST EXCEPTION");
            }
            CompletableFuture<CallTransactionResult> resultFuture = new CompletableFuture<>();
            mHandler.postDelayed(() -> {
                if (mType == SUCCESS) {
                    mLog.append(mName).append(" success;\n");
                    resultFuture.complete(
                            new CallTransactionResult(CallTransactionResult.RESULT_SUCCEED,
                                    null));
                } else if (mType == FAILED) {
                    mLog.append(mName).append(" failed;\n");
                    resultFuture.complete(
                            new CallTransactionResult(CallException.CODE_ERROR_UNKNOWN,
                                    null));
                } else {
                    mLog.append(mName).append(" timeout;\n");
                    resultFuture.complete(
                            new CallTransactionResult(CallException.CODE_ERROR_UNKNOWN,
                                    "timeout"));
                }
            }, mSleepTime);
            return resultFuture;
        }

        @Override
        public void finishTransaction() {
            isFinished = true;
        }
    }

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mTransactionManager = TransactionManager.getTestInstance();
        mLog = new StringBuilder();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mTransactionManager.clear();
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testSerialTransactionSuccess()
            throws ExecutionException, InterruptedException, TimeoutException {
        List<CallTransaction> subTransactions = new ArrayList<>();
        TestVoipCallTransaction t1 = new TestVoipCallTransaction("t1", 1000L,
                TestVoipCallTransaction.SUCCESS);
        TestVoipCallTransaction t2 = new TestVoipCallTransaction("t2", 1000L,
                TestVoipCallTransaction.SUCCESS);
        TestVoipCallTransaction t3 = new TestVoipCallTransaction("t3", 1000L,
                TestVoipCallTransaction.SUCCESS);
        subTransactions.add(t1);
        subTransactions.add(t2);
        subTransactions.add(t3);
        CompletableFuture<CallTransactionResult> resultFuture = new CompletableFuture<>();
        OutcomeReceiver<CallTransactionResult, CallException> outcomeReceiver =
                resultFuture::complete;
        String expectedLog = "t1 success;\nt2 success;\nt3 success;\n";
        mTransactionManager.addTransaction(new SerialTransaction(subTransactions, mLock),
                outcomeReceiver);
        assertEquals(CallTransactionResult.RESULT_SUCCEED,
                resultFuture.get(5000L, TimeUnit.MILLISECONDS).getResult());
        assertEquals(expectedLog, mLog.toString());
        verifyTransactionsFinished(t1, t2, t3);
    }

    @SmallTest
    @Test
    public void testSerialTransactionFailed()
            throws ExecutionException, InterruptedException, TimeoutException {
        List<CallTransaction> subTransactions = new ArrayList<>();
        TestVoipCallTransaction t1 = new TestVoipCallTransaction("t1", 1000L,
                TestVoipCallTransaction.SUCCESS);
        TestVoipCallTransaction t2 = new TestVoipCallTransaction("t2", 1000L,
                TestVoipCallTransaction.FAILED);
        TestVoipCallTransaction t3 = new TestVoipCallTransaction("t3", 1000L,
                TestVoipCallTransaction.SUCCESS);
        subTransactions.add(t1);
        subTransactions.add(t2);
        subTransactions.add(t3);
        CompletableFuture<String> exceptionFuture = new CompletableFuture<>();
        OutcomeReceiver<CallTransactionResult, CallException> outcomeReceiver =
                new OutcomeReceiver<CallTransactionResult, CallException>() {
                    @Override
                    public void onResult(CallTransactionResult result) {

                    }

                    @Override
                    public void onError(CallException e) {
                        exceptionFuture.complete(e.getMessage());
                    }
                };
        mTransactionManager.addTransaction(new SerialTransaction(subTransactions, mLock),
                outcomeReceiver);
        exceptionFuture.get(5000L, TimeUnit.MILLISECONDS);
        String expectedLog = "t1 success;\nt2 failed;\n";
        assertEquals(expectedLog, mLog.toString());
        verifyTransactionsFinished(t1, t2, t3);
    }

    @SmallTest
    @Test
    public void testParallelTransactionSuccess()
            throws ExecutionException, InterruptedException, TimeoutException {
        List<CallTransaction> subTransactions = new ArrayList<>();
        TestVoipCallTransaction t1 = new TestVoipCallTransaction("t1", 1000L,
                TestVoipCallTransaction.SUCCESS);
        TestVoipCallTransaction t2 = new TestVoipCallTransaction("t2", 500L,
                TestVoipCallTransaction.SUCCESS);
        TestVoipCallTransaction t3 = new TestVoipCallTransaction("t3", 200L,
                TestVoipCallTransaction.SUCCESS);
        subTransactions.add(t1);
        subTransactions.add(t2);
        subTransactions.add(t3);
        CompletableFuture<CallTransactionResult> resultFuture = new CompletableFuture<>();
        OutcomeReceiver<CallTransactionResult, CallException> outcomeReceiver =
                resultFuture::complete;
        mTransactionManager.addTransaction(new ParallelTransaction(subTransactions, mLock),
                outcomeReceiver);
        assertEquals(CallTransactionResult.RESULT_SUCCEED,
                resultFuture.get(5000L, TimeUnit.MILLISECONDS).getResult());
        String log = mLog.toString();
        assertTrue(log.contains("t1 success;\n"));
        assertTrue(log.contains("t2 success;\n"));
        assertTrue(log.contains("t3 success;\n"));
        verifyTransactionsFinished(t1, t2, t3);
    }

    @SmallTest
    @Test
    public void testParallelTransactionFailed()
            throws ExecutionException, InterruptedException, TimeoutException {
        List<CallTransaction> subTransactions = new ArrayList<>();
        TestVoipCallTransaction t1 = new TestVoipCallTransaction("t1", 1000L,
                TestVoipCallTransaction.SUCCESS);
        TestVoipCallTransaction t2 = new TestVoipCallTransaction("t2", 500L,
                TestVoipCallTransaction.FAILED);
        TestVoipCallTransaction t3 = new TestVoipCallTransaction("t3", 200L,
                TestVoipCallTransaction.SUCCESS);
        subTransactions.add(t1);
        subTransactions.add(t2);
        subTransactions.add(t3);
        CompletableFuture<String> exceptionFuture = new CompletableFuture<>();
        OutcomeReceiver<CallTransactionResult, CallException> outcomeReceiver =
                new OutcomeReceiver<>() {
            @Override
            public void onResult(CallTransactionResult result) {

            }

            @Override
            public void onError(CallException e) {
                exceptionFuture.complete(e.getMessage());
            }
        };
        mTransactionManager.addTransaction(new ParallelTransaction(subTransactions, mLock),
                outcomeReceiver);
        exceptionFuture.get(5000L, TimeUnit.MILLISECONDS);
        assertTrue(mLog.toString().contains("t2 failed;\n"));
        verifyTransactionsFinished(t1, t2, t3);
    }

    @SmallTest
    @Test
    public void testTransactionTimeout()
            throws ExecutionException, InterruptedException, TimeoutException {
        TestVoipCallTransaction t = new TestVoipCallTransaction("t", 10000L,
                TestVoipCallTransaction.SUCCESS);
        CompletableFuture<String> exceptionFuture = new CompletableFuture<>();
        OutcomeReceiver<CallTransactionResult, CallException> outcomeReceiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(CallTransactionResult result) {

                    }

                    @Override
                    public void onError(CallException e) {
                        exceptionFuture.complete(e.getMessage());
                    }
                };
        mTransactionManager.addTransaction(t, outcomeReceiver);
        String message = exceptionFuture.get(7000L, TimeUnit.MILLISECONDS);
        assertTrue(message.contains("timeout"));
        verifyTransactionsFinished(t);
    }

    @SmallTest
    @Test
    public void testTransactionException()
            throws ExecutionException, InterruptedException, TimeoutException {
        TestVoipCallTransaction t1 = new TestVoipCallTransaction("t1", 1000L,
                TestVoipCallTransaction.EXCEPTION);
        TestVoipCallTransaction t2 = new TestVoipCallTransaction("t2", 1000L,
                TestVoipCallTransaction.SUCCESS);
        CompletableFuture<String> exceptionFuture = new CompletableFuture<>();
        OutcomeReceiver<CallTransactionResult, CallException> outcomeExceptionReceiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(CallTransactionResult result) {
                    }

                    @Override
                    public void onError(CallException e) {
                        exceptionFuture.complete(e.getMessage());
                    }
                };
        mTransactionManager.addTransaction(t1, outcomeExceptionReceiver);
        // Transaction will timeout because the Exception caused the transaction to stop processing.
        exceptionFuture.get(7000L, TimeUnit.MILLISECONDS);
        assertTrue(mLog.toString().contains("t1 exception;\n"));
        // Verify an exception in a processing a previous transaction does not stall the next one.
        CompletableFuture<CallTransactionResult> resultFuture = new CompletableFuture<>();
        OutcomeReceiver<CallTransactionResult, CallException> outcomeReceiver =
                resultFuture::complete;
        mTransactionManager.addTransaction(t2, outcomeReceiver);
        String expectedLog = "t1 exception;\nt2 success;\n";
        assertEquals(CallTransactionResult.RESULT_SUCCEED,
                resultFuture.get(5000L, TimeUnit.MILLISECONDS).getResult());
        assertEquals(expectedLog, mLog.toString());
        verifyTransactionsFinished(t1, t2);
    }

    /**
     * This test verifies that if a transaction encounters an exception while processing it,
     * the exception finishes the transaction immediately instead of waiting for the timeout.
     */
    @SmallTest
    @Test
    public void testTransactionHitsException()
            throws ExecutionException, InterruptedException, TimeoutException {
        // GIVEN - a transaction that throws an exception when processing
        TestVoipCallTransaction t1 = new TestVoipCallTransaction(
                "t1",
                100L,
                TestVoipCallTransaction.EXCEPTION);
        // verify the TransactionManager informs the client of the failed transaction
        CompletableFuture<String> exceptionFuture = new CompletableFuture<>();
        OutcomeReceiver<CallTransactionResult, CallException> outcomeExceptionReceiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(CallTransactionResult result) {
                    }

                    @Override
                    public void onError(CallException e) {
                        exceptionFuture.complete(e.getMessage());
                    }
                };
        // WHEN - add and process the transaction
        mTransactionManager.addTransaction(t1, outcomeExceptionReceiver);
        exceptionFuture.get(200L, TimeUnit.MILLISECONDS);
        // THEN - assert the transaction finished and failed
        assertTrue(mLog.toString().contains("t1 exception;\n"));
        verifyTransactionsFinished(t1);
    }

    @SmallTest
    @Test
    public void testTransactionResultException()
            throws ExecutionException, InterruptedException, TimeoutException {
        TestVoipCallTransaction t1 = new TestVoipCallTransaction("t1", 1000L,
                TestVoipCallTransaction.SUCCESS);
        TestVoipCallTransaction t2 = new TestVoipCallTransaction("t2", 1000L,
                TestVoipCallTransaction.SUCCESS);
        TestVoipCallTransaction t3 = new TestVoipCallTransaction("t3", 1000L,
                TestVoipCallTransaction.SUCCESS);
        OutcomeReceiver<CallTransactionResult, CallException> outcomeExceptionReceiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(CallTransactionResult result) {
                        throw new IllegalStateException("RESULT EXCEPTION");
                    }

                    @Override
                    public void onError(CallException e) {
                    }
                };
        mTransactionManager.addTransaction(t1, outcomeExceptionReceiver);
        OutcomeReceiver<CallTransactionResult, CallException> outcomeException2Receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(CallTransactionResult result) {
                    }

                    @Override
                    public void onError(CallException e) {
                        throw new IllegalStateException("RESULT EXCEPTION");
                    }
                };
        mTransactionManager.addTransaction(t2, outcomeException2Receiver);
        // Verify an exception in a previous transaction result does not stall the next one.
        CompletableFuture<CallTransactionResult> resultFuture = new CompletableFuture<>();
        OutcomeReceiver<CallTransactionResult, CallException> outcomeReceiver =
                resultFuture::complete;
        mTransactionManager.addTransaction(t3, outcomeReceiver);
        String expectedLog = "t1 success;\nt2 success;\nt3 success;\n";
        assertEquals(CallTransactionResult.RESULT_SUCCEED,
                resultFuture.get(5000L, TimeUnit.MILLISECONDS).getResult());
        assertEquals(expectedLog, mLog.toString());
        verifyTransactionsFinished(t1, t2, t3);
    }

    public void verifyTransactionsFinished(TestVoipCallTransaction... transactions) {
        for (TestVoipCallTransaction t : transactions) {
            assertTrue("TestVoipCallTransaction[" + t.mName + "] never called finishTransaction",
                    t.isFinished);
        }
    }
}

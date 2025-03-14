
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
package com.android.server.telecom.callsequencing.voip;

import android.os.Bundle;
import android.os.ResultReceiver;
import android.telecom.CallEndpoint;
import android.telecom.CallException;
import android.util.Log;

import com.android.server.telecom.CallsManager;
import com.android.server.telecom.callsequencing.CallTransaction;
import com.android.server.telecom.callsequencing.CallTransactionResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class EndpointChangeTransaction extends CallTransaction {
    private static final String TAG = EndpointChangeTransaction.class.getSimpleName();
    private final CallEndpoint mCallEndpoint;
    private final CallsManager mCallsManager;

    public EndpointChangeTransaction(CallEndpoint endpoint, CallsManager callsManager) {
        super(callsManager.getLock());
        mCallEndpoint = endpoint;
        mCallsManager = callsManager;
    }

    @Override
    public CompletionStage<CallTransactionResult> processTransaction(Void v) {
        Log.i(TAG, "processTransaction");
        CompletableFuture<CallTransactionResult> future = new CompletableFuture<>();
        mCallsManager.requestCallEndpointChange(mCallEndpoint, new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                Log.i(TAG, "processTransaction: code=" + resultCode);
                if (resultCode == CallEndpoint.ENDPOINT_OPERATION_SUCCESS) {
                    future.complete(new CallTransactionResult(
                            CallTransactionResult.RESULT_SUCCEED, null));
                } else {
                    // TODO:: define errors in CallException class. b/335703584
                    future.complete(new CallTransactionResult(
                            CallException.CODE_ERROR_UNKNOWN, null));
                }
            }
        });
        return future;
    }
}

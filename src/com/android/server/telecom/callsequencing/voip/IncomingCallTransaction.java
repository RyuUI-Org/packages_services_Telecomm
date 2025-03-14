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

import static android.telecom.CallAttributes.CALL_CAPABILITIES_KEY;
import static android.telecom.CallAttributes.DISPLAY_NAME_KEY;

import static com.android.server.telecom.callsequencing.voip.VideoStateTranslation
        .TransactionalVideoStateToVideoProfileState;

import android.os.Bundle;
import android.telecom.CallAttributes;
import android.telecom.CallException;
import android.telecom.TelecomManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallsManager;
import com.android.server.telecom.callsequencing.CallTransaction;
import com.android.server.telecom.callsequencing.CallTransactionResult;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class IncomingCallTransaction extends CallTransaction {

    private static final String TAG = IncomingCallTransaction.class.getSimpleName();
    private final String mCallId;
    private final CallAttributes mCallAttributes;
    private final CallsManager mCallsManager;
    private final Bundle mExtras;
    private FeatureFlags mFeatureFlags;

    public void setFeatureFlags(FeatureFlags featureFlags) {
        mFeatureFlags = featureFlags;
    }

    public IncomingCallTransaction(String callId, CallAttributes callAttributes,
            CallsManager callsManager, Bundle extras, FeatureFlags featureFlags) {
        super(callsManager.getLock());
        mExtras = extras;
        mCallId = callId;
        mCallAttributes = callAttributes;
        mCallsManager = callsManager;
        mFeatureFlags = featureFlags;
    }

    public IncomingCallTransaction(String callId, CallAttributes callAttributes,
            CallsManager callsManager, FeatureFlags featureFlags) {
        this(callId, callAttributes, callsManager, new Bundle(), featureFlags);
    }

    @Override
    public CompletionStage<CallTransactionResult> processTransaction(Void v) {
        Log.d(TAG, "processTransaction");

        if (mCallsManager.isIncomingCallPermitted(mCallAttributes.getPhoneAccountHandle())) {
            Log.d(TAG, "processTransaction: incoming call permitted");

            Call call = mCallsManager.processIncomingCallIntent(
                    mCallAttributes.getPhoneAccountHandle(),
                    generateExtras(mCallAttributes), false);

            return CompletableFuture.completedFuture(
                    new CallTransactionResult(
                            CallTransactionResult.RESULT_SUCCEED, call, "success", true));
        } else {
            Log.d(TAG, "processTransaction: incoming call is not permitted at this time");

            return CompletableFuture.completedFuture(
                    new CallTransactionResult(
                            CallException.CODE_CALL_NOT_PERMITTED_AT_PRESENT_TIME,
                            "incoming call not permitted at the current time"));
        }
    }

    @VisibleForTesting
    public Bundle generateExtras(CallAttributes callAttributes) {
        mExtras.putString(TelecomManager.TRANSACTION_CALL_ID_KEY, mCallId);
        mExtras.putInt(CALL_CAPABILITIES_KEY, callAttributes.getCallCapabilities());
        if (mFeatureFlags.transactionalVideoState()) {
            // Transactional calls need to remap the CallAttributes video state to the existing
            // VideoProfile for consistency.
            mExtras.putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE,
                    TransactionalVideoStateToVideoProfileState(callAttributes.getCallType()));
        } else {
            mExtras.putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE,
                    callAttributes.getCallType());
        }
        mExtras.putCharSequence(DISPLAY_NAME_KEY, callAttributes.getDisplayName());
        mExtras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                callAttributes.getAddress());
        return mExtras;
    }
}

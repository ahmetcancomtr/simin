/*
 * Copyright 2017 Google Inc. All Rights Reserved.
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
package billing;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.FeatureType;
import com.android.billingclient.api.BillingClient.SkuType;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.Purchase.PurchasesResult;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles all the interactions with Play Store (via Billing library), maintains connection to
 * it through BillingClient and caches temporary states/data if needed
 */
public class BillingManager implements PurchasesUpdatedListener {
    // Default value of mBillingClientResponseCode until BillingManager was not yeat initialized
    public static final int BILLING_MANAGER_NOT_INITIALIZED  = -1;

    private static final String TAG = "BillingManager";

    /** A reference to BillingClient **/
    private BillingClient mBillingClient;

    /**
     * True if billing service is connected now.
     */
    private boolean mIsServiceConnected;

    private final BillingUpdatesListener mBillingUpdatesListener;

    private final Activity mActivity;

    private final List<Purchase> mPurchases = new ArrayList<>();

    private Set<String> mTokensToBeConsumed;

    private int mBillingClientResponseCode = BILLING_MANAGER_NOT_INITIALIZED;

    /* BASE_64_ENCODED_PUBLIC_KEY should be YOUR APPLICATION'S PUBLIC KEY
     * (that you got from the Google Play developer console). This is not your
     * developer public key, it's the *app-specific* public key.
     *
     * Instead of just storing the entire literal string here embedded in the
     * program,  construct the key at runtime from pieces or
     * use bit manipulation (for example, XOR with some other string) to hide
     * the actual key.  The key itself is not secret information, but we don't
     * want to make it easy for an attacker to replace the public key with one
     * of their own and then fake messages from the server.
     */
  //AC  private static final String BASE_64_ENCODED_PUBLIC_KEY = "CONSTRUCT_YOUR_KEY_AND_PLACE_IT_HERE";
    private static final String BASE_64_ENCODED_PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmkU/a3dumyBKKXQZLh4X6HAjtKXHoSnoEU3OU2/EBY1jX++vM5SV3tzgNDBp0Bonow2jyTZCQrFS8GuOWd6iG2mmjnGK92Y79RT+qQ+pMyJOzq3ChDvYNUQAyLeuAqQgWGkqnroSs8ENRngwbC1C5xARMs0Tiqr891ijw1tRgwXYvHl+FA/9ARtgc9mOCVh4i96JwcYirVlY9I98ciG1O5iMJGcOeRrqfJWWB6uxUEqGa++A9wb3k0YIIH0kS2FI3+fAVtuR3oAuv2sjM3VCAkYgFRAbhcb5kTjlZxwDVljFTPMTvt8KF3lSm8Q3OGjnxdbEN0GeVIK1504NGdKk/wIDAQAB";

    /**
     * Listener to the updates that happen when purchases list was updated or consumption of the
     * item was finished
     */
    public interface BillingUpdatesListener {
        void onBillingClientSetupFinished();
        void onConsumeFinished(String token, @BillingClient.BillingResponseCode int result);
        void onPurchasesUpdated(List<Purchase> purchases);
        void onUserCancelFlow();
        void onError();
    }

    /**
     * Listener for the Billing client state to become connected
     */
    public interface ServiceConnectedListener {
        void onServiceConnected(@BillingClient.BillingResponseCode int resultCode);
    }

    public BillingManager(Activity activity, final BillingUpdatesListener updatesListener) {
        Log.d(TAG, "Creating Billing client.");
        mActivity = activity;
        mBillingUpdatesListener = updatesListener;
        mBillingClient = BillingClient.newBuilder(mActivity).setListener(this).enablePendingPurchases().build();

        Log.d(TAG, "Starting setup.");

        // Start setup. This is asynchronous and the specified listener will be called
        // once setup completes.
        // It also starts to report all the new purchases through onPurchasesUpdated() callback.
        startServiceConnection(new Runnable() {
            @Override
            public void run() {
                // Notifying the listener that billing client is ready
                mBillingUpdatesListener.onBillingClientSetupFinished();
                // IAB is fully set up. Now, let's get an inventory of stuff we own.
                Log.d(TAG, "Setup successful. Querying inventory.");
                queryPurchases();
            }
        });
    }

    /**
     * Handle a callback that purchases were updated from the Billing library
     */
    @Override
    public void onPurchasesUpdated(BillingResult resultCode, List<Purchase> purchases) {

        if (resultCode.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            if(purchases!=null){
                for (Purchase purchase : purchases) {
                    handlePurchase(purchase);
                }
            }
            else{
                mBillingUpdatesListener.onError();
                Log.w(TAG, "Purchase list is null");
                return;
            }

            mBillingUpdatesListener.onPurchasesUpdated(mPurchases);

        } else if (resultCode.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.i(TAG, "onPurchasesUpdated() - user cancelled the purchase flow - skipping");
            mBillingUpdatesListener.onUserCancelFlow();


        } else {
            Log.w(TAG, "onPurchasesUpdated() got unknown resultCode: " + resultCode);
        }
    }

    /**
     * Start a purchase flow
     */
   /* public void initiatePurchaseFlow(final String skuId, final @SkuType String billingType) {
        initiatePurchaseFlow(skuId, null, billingType);
    }*/

    /**
     * Start a purchase or subscription replace flow
     */
  /*  public void initiatePurchaseFlow(final String skuId, final ArrayList<String> oldSkus,
            final @SkuType String billingType) {
        Runnable purchaseFlowRequest = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Launching in-app purchase flow. Replace old SKU? " + (oldSkus != null));
                BillingFlowParams purchaseParams = BillingFlowParams.newBuilder()
                        .setSku(skuId).setType(billingType).setOldSkus(skuId,oldSkus).build();
                mBillingClient.launchBillingFlow(mActivity, purchaseParams);
            }
        };

        executeServiceRequest(purchaseFlowRequest);
    }*/

    public void initiatePurchaseFlow(final SkuDetails skuDetails) {
        Runnable purchaseFlowRequest = new Runnable() {
            @Override
            public void run() {
                BillingFlowParams purchaseParams = BillingFlowParams.newBuilder()
                        .setSkuDetails(skuDetails).build();
                mBillingClient.launchBillingFlow(mActivity, purchaseParams);
            }
        };

        executeServiceRequest(purchaseFlowRequest);
    }
    public Context getContext() {
        return mActivity;
    }

    /**
     * Clear the resources
     */
    public void destroy() {
        Log.d(TAG, "Destroying the manager.");

        if (mBillingClient != null && mBillingClient.isReady()) {
            mBillingClient.endConnection();
            mBillingClient = null;
        }
    }

    public void querySkuDetailsAsync(@SkuType final String itemType, final List<String> skuList,
                                     final SkuDetailsResponseListener listener) {
        // Creating a runnable from the request to use it inside our connection retry policy below
        Runnable queryRequest = new Runnable() {
            @Override
            public void run() {
                // Query the purchase async

                SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
                params.setSkusList(skuList).setType(itemType);
                mBillingClient.querySkuDetailsAsync(params.build(),
                        new SkuDetailsResponseListener() {
                            @Override
                            public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> skuDetailsList) {
                                listener.onSkuDetailsResponse(billingResult, skuDetailsList);
                            }

                        });
            }
        };

        executeServiceRequest(queryRequest);
    }

    public void consumeAsync(final String purchaseToken) {
        // If we've already scheduled to consume this token - no action is needed (this could happen
        // if you received the token when querying purchases inside onReceive() and later from
        // onActivityResult()
        if (mTokensToBeConsumed == null) {
            mTokensToBeConsumed = new HashSet<>();
        } else if (mTokensToBeConsumed.contains(purchaseToken)) {
            Log.i(TAG, "Token was already scheduled to be consumed - skipping...");
            return;
        }
        mTokensToBeConsumed.add(purchaseToken);

        // Generating Consume Response listener
        final ConsumeResponseListener onConsumeListener = new ConsumeResponseListener() {
            @Override
            public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {

                // If billing service was disconnected, we try to reconnect 1 time
                // (feel free to introduce your retry policy here).
                mBillingUpdatesListener.onConsumeFinished(purchaseToken, billingResult.getResponseCode());
            }
        };

        // Creating a runnable from the request to use it inside our connection retry policy below
        Runnable consumeRequest = new Runnable() {
            @Override
            public void run() {
                // Consume the purchase async

                mBillingClient.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build(),
                        onConsumeListener);
            }
        };

        executeServiceRequest(consumeRequest);
    }

    /**
     * Returns the value Billing client response code or BILLING_MANAGER_NOT_INITIALIZED if the
     * clien connection response was not received yet.
     */
    public int getBillingClientResponseCode() {
        return mBillingClientResponseCode;
    }

    /**
     * Handles the purchase
     * <p>Note: Notice that for each purchase, we check if signature is valid on the client.
     * It's recommended to move this check into your backend.
     * See {@link Security#verifyPurchase(String, String, String)}
     * </p>
     * @param purchase Purchase to be handled
     */
    private void handlePurchase(Purchase purchase) {
        if (!verifyValidSignature(purchase.getOriginalJson(), purchase.getSignature())) {
            Log.i(TAG, "Got a purchase: " + purchase + "; but signature is bad. Skipping...");
            return;
        }

        Log.d(TAG, "Got a verified purchase: " + purchase);

        mPurchases.add(purchase);
    }

    /**
     * Handle a result from querying of purchases and report an updated list to the listener
     */
    private void onQueryPurchasesFinished(PurchasesResult result) {
        // Have we been disposed of in the meantime? If so, or bad result code, then quit
        if (mBillingClient == null || result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "Billing client was null or result code (" + result.getResponseCode()
                    + ") was bad - quitting");
            return;
        }

        Log.d(TAG, "Query inventory was successful.");

        // Update the UI and purchases inventory with new list of purchases
        mPurchases.clear();
        onPurchasesUpdated(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(),
                result.getPurchasesList());
    }

    /**
     * Checks if subscriptions are supported for current client
     * <p>Note: This method does not automatically retry for RESULT_SERVICE_DISCONNECTED.
     * It is only used in unit tests and after queryPurchases execution, which already has
     * a retry-mechanism implemented.
     * </p>
     */
    public boolean areSubscriptionsSupported() {
        int responseCode = mBillingClient.isFeatureSupported(FeatureType.SUBSCRIPTIONS).getResponseCode();
        if (responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "areSubscriptionsSupported() got an error response: " + responseCode);
        }
        return responseCode == BillingClient.BillingResponseCode.OK;
    }

    /**
     * Query purchases across various use cases and deliver the result in a formalized way through
     * a listener
     */
    public void queryPurchases() {
        Runnable queryToExecute = new Runnable() {
            @Override
            public void run() {
                long time = System.currentTimeMillis();
                PurchasesResult purchasesResult = mBillingClient.queryPurchases(SkuType.INAPP);
                Log.i(TAG, "Querying purchases elapsed time: " + (System.currentTimeMillis() - time)
                        + "ms");
                // If there are subscriptions supported, we add subscription rows as well
                if (areSubscriptionsSupported()) {
                    PurchasesResult subscriptionResult
                            = mBillingClient.queryPurchases(SkuType.SUBS);
                    Log.i(TAG, "Querying purchases and subscriptions elapsed time: "
                            + (System.currentTimeMillis() - time) + "ms");
                    Log.i(TAG, "Querying subscriptions result code: "
                            + subscriptionResult.getResponseCode()
                            + " res: " + subscriptionResult.getPurchasesList().size());

                    if (subscriptionResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        purchasesResult.getPurchasesList().addAll(
                                subscriptionResult.getPurchasesList());
                    } else {
                        Log.e(TAG, "Got an error response trying to query subscription purchases");
                    }
                } else if (purchasesResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Skipped subscription purchases query since they are not supported");
                } else {
                    Log.w(TAG, "queryPurchases() got an error response code: "
                            + purchasesResult.getResponseCode());
                }
                onQueryPurchasesFinished(purchasesResult);
            }
        };

        executeServiceRequest(queryToExecute);
    }

    public void startServiceConnection(final Runnable executeOnSuccess) {
        mBillingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                Log.d(TAG, "Setup finished. Response code: " + billingResult.getResponseCode());

                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    mIsServiceConnected = true;
                    if (executeOnSuccess != null) {
                        executeOnSuccess.run();
                    }
                }
                mBillingClientResponseCode = billingResult.getResponseCode();
            }

            @Override
            public void onBillingServiceDisconnected() {
                mIsServiceConnected = false;
            }
        });
    }

    private void executeServiceRequest(Runnable runnable) {
        if (mIsServiceConnected) {
            runnable.run();
        } else {
            // If billing service was disconnected, we try to reconnect 1 time.
            // (feel free to introduce your retry policy here).
            startServiceConnection(runnable);
        }
    }

    /**
     * Verifies that the purchase was signed correctly for this developer's public key.
     * <p>Note: It's strongly recommended to perform such check on your backend since hackers can
     * replace this method with "constant true" if they decompile/rebuild your app.
     * </p>
     */
    private boolean verifyValidSignature(String signedData, String signature) {
        // Some sanity checks to see if the developer (that's you!) really followed the
        // instructions to run this sample (don't put these checks on your app!)
        if (BASE_64_ENCODED_PUBLIC_KEY.contains("CONSTRUCT_YOUR")) {
            throw new RuntimeException("Please update your app's public key at: "
                    + "BASE_64_ENCODED_PUBLIC_KEY");
        }

        try {
            return Security.verifyPurchase(BASE_64_ENCODED_PUBLIC_KEY, signedData, signature);
        } catch (IOException e) {
            Log.e(TAG, "Got an exception trying to validate a purchase: " + e);
            return false;
        }
    }
}


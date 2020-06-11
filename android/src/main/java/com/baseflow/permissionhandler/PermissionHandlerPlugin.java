package com.baseflow.permissionhandler;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build.VERSION_CODES;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener;

public class PermissionHandlerPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler {
    private static final String LOG_TAG = "permissions_handler";
    private static final int PERMISSION_CODE = 24;
    private static final int PERMISSION_CODE_IGNORE_BATTERY_OPTIMIZATIONS = 5672353;

    //PERMISSION_GROUP
    private static final int PERMISSION_GROUP_CAMERA = 0;
    private static final int PERMISSION_GROUP_PHONE = 1;
    private static final int PERMISSION_GROUP_PHOTOS = 2;
    private static final int PERMISSION_GROUP_UNKNOWN = 3;

    private Context context;
    private Activity activity;

    public PermissionHandlerPlugin() {

    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        final MethodChannel channel = new MethodChannel(binding.getBinaryMessenger(), "flutter.baseflow.com/permissions/methods");
        channel.setMethodCallHandler(this);
        context = binding.getApplicationContext();
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {

    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(new PluginRegistry.RequestPermissionsResultListener() {
            @Override
            public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
                if (requestCode == PERMISSION_CODE) {
                    handlePermissionsRequest(permissions, grantResults);
                    return true;
                } else {
                    return false;
                }
            }
        });

        binding.addActivityResultListener(new ActivityResultListener() {
            @Override
            public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
                return false;
            }
        });
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {

    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {

    }

    @Override
    public void onDetachedFromActivity() {

    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            PERMISSION_GROUP_CAMERA,
            PERMISSION_GROUP_PHONE,
            PERMISSION_GROUP_PHOTOS,
            PERMISSION_GROUP_UNKNOWN,
    })
    private @interface PermissionGroup {
    }

    //PERMISSION_STATUS
    private static final int PERMISSION_STATUS_DENIED = 0;
    private static final int PERMISSION_STATUS_DISABLED = 1;
    private static final int PERMISSION_STATUS_GRANTED = 2;
    private static final int PERMISSION_STATUS_RESTRICTED = 3;
    private static final int PERMISSION_STATUS_UNKNOWN = 4;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            PERMISSION_STATUS_DENIED,
            PERMISSION_STATUS_DISABLED,
            PERMISSION_STATUS_GRANTED,
            PERMISSION_STATUS_RESTRICTED,
            PERMISSION_STATUS_UNKNOWN,
    })
    private @interface PermissionStatus {
    }


    //SERVICE_STATUS
    private static final int SERVICE_STATUS_DISABLED = 0;
    private static final int SERVICE_STATUS_ENABLED = 1;
    private static final int SERVICE_STATUS_NOT_APPLICABLE = 2;
    private static final int SERVICE_STATUS_UNKNOWN = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            SERVICE_STATUS_DISABLED,
            SERVICE_STATUS_ENABLED,
            SERVICE_STATUS_NOT_APPLICABLE,
            SERVICE_STATUS_UNKNOWN,
    })
    private @interface ServiceStatus {
    }

    @PermissionGroup
    private static int parseManifestName(String permission) {
        switch (permission) {

            case Manifest.permission.CAMERA:
                return PERMISSION_GROUP_CAMERA;
            case Manifest.permission.READ_PHONE_STATE:
            case Manifest.permission.CALL_PHONE:
            case Manifest.permission.READ_CALL_LOG:
            case Manifest.permission.WRITE_CALL_LOG:
            case Manifest.permission.ADD_VOICEMAIL:
            case Manifest.permission.USE_SIP:
            case Manifest.permission.PROCESS_OUTGOING_CALLS:
                return PERMISSION_GROUP_PHONE;
            default:
                return PERMISSION_GROUP_UNKNOWN;
        }
    }

    private Result mResult;
    private ArrayList<String> mRequestedPermissions;
    @SuppressLint("UseSparseArrays")
    private Map<Integer, Integer> mRequestResults = new HashMap<>();

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        switch (call.method) {
            case "checkPermissionStatus": {
                @PermissionGroup final int permission = (int) call.arguments;
                @PermissionStatus final int permissionStatus = checkPermissionStatus(permission);

                result.success(permissionStatus);
                break;
            }
            case "checkServiceStatus": {
                @PermissionGroup final int permission = (int) call.arguments;
                @ServiceStatus final int serviceStatus = checkServiceStatus(permission);

                result.success(serviceStatus);
                break;
            }
            case "requestPermissions":
                if (mResult != null) {
                    result.error(
                            "ERROR_ALREADY_REQUESTING_PERMISSIONS",
                            "A request for permissions is already running, please wait for it to finish before doing another request (note that you can request multiple permissions at the same time).",
                            null);
                    return;
                }

                mResult = result;
                final List<Integer> permissions = call.arguments();
                requestPermissions(permissions);
                break;
            case "shouldShowRequestPermissionRationale": {
                @PermissionGroup final int permission = (int) call.arguments;
                result.success(shouldShowRequestPermissionRationale(permission));
                break;
            }
            case "openAppSettings":
                boolean isOpen = openAppSettings();
                result.success(isOpen);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    @PermissionStatus
    private int checkPermissionStatus(@PermissionGroup int permission) {
        final List<String> names = getManifestNames(permission);

        if (names == null) {
            Log.d(LOG_TAG, "No android specific permissions needed for: " + permission);

            return PERMISSION_STATUS_GRANTED;
        }

        //if no permissions were found then there is an issue and permission is not set in Android manifest
        if (names.size() == 0) {
            Log.d(LOG_TAG, "No permissions found in manifest for: " + permission);
            return PERMISSION_STATUS_UNKNOWN;
        }

        if (context == null) {
            Log.d(LOG_TAG, "Unable to detect current Activity or App Context.");
            return PERMISSION_STATUS_UNKNOWN;
        }

        final boolean targetsMOrHigher = context.getApplicationInfo().targetSdkVersion >= VERSION_CODES.M;

        for (String name : names) {
            // Only handle them if the client app actually targets a API level greater than M.
            if (targetsMOrHigher) {
                final int permissionStatus = ContextCompat.checkSelfPermission(context, name);
                if (permissionStatus == PackageManager.PERMISSION_DENIED) {
                    return PERMISSION_STATUS_DENIED;
                } else if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                    return PERMISSION_STATUS_UNKNOWN;
                }
            }
        }

        return PERMISSION_STATUS_GRANTED;
    }

    @ServiceStatus
    private int checkServiceStatus(int permission) {
        if (context == null) {
            Log.d(LOG_TAG, "Unable to detect current Activity or App Context.");
            return SERVICE_STATUS_UNKNOWN;
        }

        if (permission == PERMISSION_GROUP_PHONE) {
            PackageManager pm = context.getPackageManager();
            if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                return SERVICE_STATUS_NOT_APPLICABLE;
            }

            TelephonyManager telephonyManager = (TelephonyManager) context
                    .getSystemService(Context.TELEPHONY_SERVICE);

            if (telephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_NONE) {
                return SERVICE_STATUS_NOT_APPLICABLE;
            }

            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:123123"));
            List<ResolveInfo> callAppsList = pm.queryIntentActivities(callIntent, 0);

            if (callAppsList.isEmpty()) {
                return SERVICE_STATUS_NOT_APPLICABLE;
            }

            if (telephonyManager.getSimState() != TelephonyManager.SIM_STATE_READY) {
                return SERVICE_STATUS_DISABLED;
            }

            return SERVICE_STATUS_ENABLED;
        }

        return SERVICE_STATUS_NOT_APPLICABLE;
    }

    private boolean shouldShowRequestPermissionRationale(int permission) {
        if (activity == null) {
            Log.d(LOG_TAG, "Unable to detect current Activity.");
            return false;
        }

        List<String> names = getManifestNames(permission);

        // if isn't an android specific group then go ahead and return false;
        if (names == null) {
            Log.d(LOG_TAG, "No android specific permissions needed for: " + permission);
            return false;
        }

        if (names.isEmpty()) {
            Log.d(LOG_TAG, "No permissions found in manifest for: " + permission + " no need to show request rationale");
            return false;
        }

        //noinspection LoopStatementThatDoesntLoop
        for (String name : names) {
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, name);
        }

        return false;
    }

    private void requestPermissions(List<Integer> permissions) {
        if (activity == null) {
            Log.d(LOG_TAG, "Unable to detect current Activity.");

            for (Integer permission : permissions) {
                mRequestResults.put(permission, PERMISSION_STATUS_UNKNOWN);
            }

            processResult();
            return;
        }

        ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (Integer permission : permissions) {
            @PermissionStatus final int permissionStatus = checkPermissionStatus(permission);
            if (permissionStatus != PERMISSION_STATUS_GRANTED) {
                final List<String> names = getManifestNames(permission);

                //check to see if we can find manifest names
                //if we can't add as unknown and continue
                if (names == null || names.isEmpty()) {
                    if (!mRequestResults.containsKey(permission)) {
                        mRequestResults.put(permission, PERMISSION_STATUS_UNKNOWN);
                    }

                    continue;
                }

                permissionsToRequest.addAll(names);
            } else {
                if (!mRequestResults.containsKey(permission)) {
                    mRequestResults.put(permission, PERMISSION_STATUS_GRANTED);
                }
            }
        }

        final String[] requestPermissions = permissionsToRequest.toArray(new String[0]);
        if (permissionsToRequest.size() > 0) {
            ActivityCompat.requestPermissions(activity, requestPermissions, PERMISSION_CODE);
        } else if (mRequestResults.size() > 0) {
            processResult();
        }
    }

    private void handlePermissionsRequest(String[] permissions, int[] grantResults) {
        if (mResult == null) {
            return;
        }

        for (int i = 0; i < permissions.length; i++) {
            @PermissionGroup final int permission = parseManifestName(permissions[i]);
            if (permission == PERMISSION_GROUP_UNKNOWN)
                continue;

            if (!mRequestResults.containsKey(permission)) {
                mRequestResults.put(permission, toPermissionStatus(grantResults[i]));
            }
        }

        processResult();
    }

    @PermissionStatus
    private int toPermissionStatus(int grantResult) {
        return grantResult == PackageManager.PERMISSION_GRANTED ? PERMISSION_STATUS_GRANTED : PERMISSION_STATUS_DENIED;
    }

    private void processResult() {
        mResult.success(mRequestResults);

        mRequestResults.clear();
        mResult = null;
    }

    private boolean openAppSettings() {
        if (context == null) {
            Log.d(LOG_TAG, "Unable to detect current Activity or App Context.");
            return false;
        }

        try {
            Intent settingsIntent = new Intent();
            settingsIntent.setAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            settingsIntent.addCategory(Intent.CATEGORY_DEFAULT);
            settingsIntent.setData(android.net.Uri.parse("package:" + context.getPackageName()));
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            context.startActivity(settingsIntent);

            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> getManifestNames(@PermissionGroup int permission) {
        final ArrayList<String> permissionNames = new ArrayList<>();

        switch (permission) {

            case PERMISSION_GROUP_CAMERA:
                if (hasPermissionInManifest(Manifest.permission.CAMERA))
                    permissionNames.add(Manifest.permission.CAMERA);
                break;

            case PERMISSION_GROUP_PHONE:
                if (hasPermissionInManifest(Manifest.permission.READ_PHONE_STATE))
                    permissionNames.add(Manifest.permission.READ_PHONE_STATE);

                if (hasPermissionInManifest(Manifest.permission.CALL_PHONE))
                    permissionNames.add(Manifest.permission.CALL_PHONE);

                if (hasPermissionInManifest(Manifest.permission.READ_CALL_LOG))
                    permissionNames.add(Manifest.permission.READ_CALL_LOG);

                if (hasPermissionInManifest(Manifest.permission.WRITE_CALL_LOG))
                    permissionNames.add(Manifest.permission.WRITE_CALL_LOG);

                if (hasPermissionInManifest(Manifest.permission.ADD_VOICEMAIL))
                    permissionNames.add(Manifest.permission.ADD_VOICEMAIL);

                if (hasPermissionInManifest(Manifest.permission.USE_SIP))
                    permissionNames.add(Manifest.permission.USE_SIP);

                if (hasPermissionInManifest(Manifest.permission.PROCESS_OUTGOING_CALLS))
                    permissionNames.add(Manifest.permission.PROCESS_OUTGOING_CALLS);
                break;

            case PERMISSION_GROUP_PHOTOS:
            case PERMISSION_GROUP_UNKNOWN:
                return null;
        }

        return permissionNames;
    }

    private boolean hasPermissionInManifest(String permission) {
        try {
            if (mRequestedPermissions != null) {
                for (String r : mRequestedPermissions) {
                    if (r.equals(permission)) {
                        return true;
                    }
                }
            }

            if (context == null) {
                Log.d(LOG_TAG, "Unable to detect current Activity or App Context.");
                return false;
            }

            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);

            if (info == null) {
                Log.d(LOG_TAG, "Unable to get Package info, will not be able to determine permissions to request.");
                return false;
            }

            mRequestedPermissions = new ArrayList<>(Arrays.asList(info.requestedPermissions));
            for (String r : mRequestedPermissions) {
                if (r.equals(permission)) {
                    return true;
                }
            }
        } catch (Exception ex) {
            Log.d(LOG_TAG, "Unable to check manifest for permission: ", ex);
        }
        return false;
    }
}

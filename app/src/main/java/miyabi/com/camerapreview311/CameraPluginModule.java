package miyabi.com.camerapreview311;

import com.unity3d.player.UnityPlayer;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.FrameLayout;

import android.view.ViewGroup;
/**
 * Created by macmin on 2016/03/11.
 */


public class CameraPluginModule {
    // load our native library
    protected final static String TAG = "CameraPluginModule";

    static {
        try {
            System.loadLibrary("cameralib");
        } catch(UnsatisfiedLinkError ule){
            Log.e(TAG, "Error while loading library <SOME LIB>", ule);
        }
    }

    private static FrameLayout layout = null;
    public static Context mContext;
    private MainView mMainView;

    public int startCamera(String msg) {
        final Activity unityActivity = UnityPlayer.currentActivity;
        mContext = unityActivity;
        unityActivity.runOnUiThread(new Runnable() {
            public void run() {
                mMainView = new MainView(unityActivity);
                 if(CameraPluginModule.layout == null) {
                    CameraPluginModule.layout = new FrameLayout(unityActivity);
                    unityActivity.addContentView(CameraPluginModule.layout, new ViewGroup.LayoutParams(-1, -1));
                    CameraPluginModule.layout.setFocusable(true);
                    CameraPluginModule.layout.setFocusableInTouchMode(true);
                }
                CameraPluginModule.layout.addView(CameraPluginModule.this.mMainView,
                        new android.widget.FrameLayout.LayoutParams(-1, -1, 0));
            }
        });
        return 1;
    }

    public void Destroy() {
        Activity a = UnityPlayer.currentActivity;
        a.runOnUiThread(new Runnable() {
            public void run() {
                if (CameraPluginModule.this.mMainView != null) {
                    CameraPluginModule.layout.removeView(CameraPluginModule.this.mMainView);
                    CameraPluginModule.this.mMainView = null;
                }
            }
        });
    }

}

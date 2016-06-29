package miyabi.com.camerapreview311;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.SurfaceHolder;

/**
 * Created by macmin on 2016/03/16.
 */
public class MainView extends GLSurfaceView {
    MainRenderer mRenderer;
    protected final static String TAG = "MainView";

    public MainView(Activity context) {
        super ( context);
        mRenderer = new MainRenderer(this, context);
        setEGLContextClientVersion(2);
        setRenderer(mRenderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY );
    }

    public void surfaceCreated ( SurfaceHolder holder ) {
        Log.v(TAG, "MainView.surfaceCreated");
        super.surfaceCreated ( holder );
    }

    public void surfaceDestroyed ( SurfaceHolder holder ) {
        Log.v(TAG, "MainView.surfaceDestroyed");
        super.surfaceDestroyed ( holder );
    }

    public void surfaceChanged ( SurfaceHolder holder, int format, int w, int h ) {
        Log.v(TAG, "MainView.surfaceChanged");
        super.surfaceChanged ( holder, format, w, h );
    }
    @Override
    public void onResume() {
        //mRenderer.onResume();
        super.onResume();
    }

    @Override
    public void onPause() {
        //mRenderer.onPause();
        super.onPause();
    }

}

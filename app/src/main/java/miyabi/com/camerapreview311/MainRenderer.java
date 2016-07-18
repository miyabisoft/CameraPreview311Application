package miyabi.com.camerapreview311;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.content.res.Configuration;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.os.CountDownTimer;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

class MainRenderer implements GLSurfaceView.Renderer , SurfaceTexture.OnFrameAvailableListener {

    private static native void sendImageData(byte[] array,  int w, int h, int ch);

    private final String vss_default = "" +
            "attribute vec2 vPosition;\n" +
            "attribute vec2 vTexCoord;\n" +
            "varying vec2 texCoord;\n" +
            "void main() {\n" +
            "  texCoord = vTexCoord;\n" +
            "  gl_Position = vec4 ( vPosition.x, vPosition.y, 0.0, 1.0 );\n" +
            "}";

    private final String fss_default = "" +
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "varying vec2 texCoord;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture,texCoord);\n" +
            "}";

    protected final static String TAG = "MainRenderer";
    private int[] hTex;
    public int mTextureID = 0;

    public int nativeTexturePointer = -1;
    private FloatBuffer pVertex;
    private FloatBuffer pTexCoord;
    private int hProgram;

    public SurfaceTexture mSTexture;

    private boolean mGLInit = false;
    private boolean mUpdateST = false;

    private MainView mView;

    private CameraRotation mCameraRotation;
    private CameraDevice mCameraDevice;
    public CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private String mCameraID;
    private boolean mConfigured = false;
    private boolean mIsPortraitDevice;
    private boolean mInitialized = false;
    private Size mImageSize;
    private Size mCameraSize;
    public Size mPreviewSize = new Size(1024, 1024 );
    //public Size mPreviewSize = new Size(1080, 1920 );

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    public Bitmap mBitdata;
    private Activity mActivity;

    private Handler mHandler;
    private Runnable updateText;
    private boolean mFlash;
    private Surface mSurface;
    private MainRenderer mMainRender;

    //  OpticalFlow
    private boolean isCalcOpticalFlowFarneback;
    private int mCalcOpticalFlowCount;
    private Mat mSecondFrame;
    private Mat mFirstFrame ;
    private Mat mFlow;
    private Mat mInputFrame;
    private Mat mOutputFrame;

    enum CameraRotation {ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270}

    private static final float TEX_COORDS_ROTATION_0[] = {
            0.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
    };
    private static final float TEX_COORDS_ROTATION_90[] = {
            1.0f, 0.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f
    };
    private static final float TEX_COORDS_ROTATION_180[] = {
            1.0f, 1.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            0.0f, 0.0f
    };
    private static final float TEX_COORDS_ROTATION_270[] = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
    };

    MainRenderer(MainView view , Activity context) {
        Log.v(TAG, "MainRenderer.MainRenderer");
        MainRenderer mMainRender = this;
        mActivity = context;
        mView = view;
        isCalcOpticalFlowFarneback = false;
        mCalcOpticalFlowCount = 0;

        float[] vtmp = { 1.0f, -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, -1.0f, 1.0f };
        float[] ttmp = { 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0.0f };
        pVertex = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        pVertex.put(vtmp);
        pVertex.position(0);
        pTexCoord = ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        pTexCoord.put(ttmp);
        pTexCoord.position(0);
        Log.v(TAG, "MainRenderer.mMatFrame");
        /** スレッドUI操作用ハンドラ */
        mFlash = false;
    }

    public void setTextureID(int id) {
        mTextureID = id;
    }

    public int getTextureID() {
        return mTextureID;
    }

    public void onResume() {
        Log.v(TAG, "MainRenderer.onResume");
        startBackgroundThread();
    }

    public void onPause() {
        Log.v(TAG, "MainRenderer.onPause");
        mGLInit = false;
        mUpdateST = false;
        closeCamera();
        stopBackgroundThread();
    }

    @Override
    public synchronized void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mUpdateST = true;
        Log.v(TAG, "MainRenderer.onFrameAvailable");
        mView.requestRender();
    }
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        initTex();
        mSTexture = new SurfaceTexture (mTextureID);
        mSTexture.setOnFrameAvailableListener(this);

        GLES20.glClearColor(1.0f, 1.0f, 1.0f, 1.0f);

        hProgram = loadShader ( vss_default, fss_default );

        android.graphics.Point ss = new android.graphics.Point();
        mView.getDisplay().getRealSize(ss);
        Log.v(TAG, "MainRenderer.onSurfaceCreated.width :" + ss.x);
        Log.v(TAG, "MainRenderer.onSurfaceCreated.height :" + ss.y);

        cacPreviewSize(ss.x, ss.y);
        startBackgroundThread();

        boolean isPortraitApp =
                mActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        int orientation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        if (isPortraitApp) {
            mIsPortraitDevice = (orientation == Surface.ROTATION_0 || orientation ==  Surface.ROTATION_180);
        } else {
            mIsPortraitDevice = (orientation == Surface.ROTATION_90 || orientation ==  Surface.ROTATION_270);
        }

        openCamera();

        mGLInit = true;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mConfigured = false;
        Log.v(TAG, "MainRenderer.onSurfaceChanged.width :" + width);
        Log.v(TAG, "MainRenderer.onSurfaceChanged.height :" + height);
    }

    private void setConfig() {

        switch(mCameraRotation) {
            case ROTATION_0:
                Log.e(TAG, "setConfig : 0"+mCameraRotation);
                pTexCoord.put(TEX_COORDS_ROTATION_0);
                break;
            case ROTATION_90:
                Log.e(TAG, "setConfig : 90"+mCameraRotation);
                pTexCoord.put(TEX_COORDS_ROTATION_90);
                break;
            case ROTATION_180:
                Log.e(TAG, "setConfig : 180"+mCameraRotation);
                pTexCoord.put(TEX_COORDS_ROTATION_180);
                break;
            case ROTATION_270:
                Log.e(TAG, "setConfig : 270"+mCameraRotation);
                pTexCoord.put(TEX_COORDS_ROTATION_270);
                break;
        }
        pTexCoord.position(0);

        GLES20.glViewport(0, 0, mImageSize.getWidth(), mImageSize.getHeight());
    }

    // 毎フレームごとに呼び出される。
    @Override
    public void onDrawFrame(GL10 gl) {
        if ( !mGLInit ) return;

        GLES20.glClearColor(0.5f, 0.5f, 1.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (!mConfigured) {
            if (mConfigured = mInitialized) {
                setCameraRotation();
                setConfig();
            } else {
                return;
            }
        }

        GLES20.glUseProgram(hProgram);

        int ph = GLES20.glGetAttribLocation(hProgram, "vPosition");
        int tch = GLES20.glGetAttribLocation ( hProgram, "vTexCoord" );

        GLES20.glVertexAttribPointer(ph, 2, GLES20.GL_FLOAT, false, 4 * 2, pVertex);
        GLES20.glVertexAttribPointer(tch, 2, GLES20.GL_FLOAT, false, 4 * 2, pTexCoord);
        GLES20.glEnableVertexAttribArray(ph);
        GLES20.glEnableVertexAttribArray(tch);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);

        GLES20.glUniform1i(GLES20.glGetUniformLocation(hProgram, "sTexture"), 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glFlush();
        synchronized(this) {
            if ( mUpdateST ) {
                mSTexture.updateTexImage();
                Log.v(TAG, "MainRenderer.onDrawFrame.updateTexImage :" + mTextureID);

                // mBitdata = getBitmap();
                // saveBitmapToSd(mBitdata); // debug bitmap to Album

                // NDKによりヒープ領域にカメラ画像データを転送..
                sendImageDatatoNative();
                //changeGrayData();
                mUpdateST = false;
            }
        }
    }

    public void updateCameraImage() {
        mSTexture.updateTexImage();
        Log.v(TAG, "updateCameraImage ID : " + mTextureID);
    }

    private void changeGrayData() {
        int width = mPreviewSize.getWidth();
        int height = mPreviewSize.getHeight();

        // GLES.Texture -> ByteBuffer
        final ByteBuffer buffer = ByteBuffer.allocate(width * height * 4);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
/*
        // buffer -> cv::Mat
        Mat mat = new Mat(height, width, CvType.CV_8UC4);
        mat.put(0, 0, buffer.array());
        // gray
        Imgproc.cvtColor(mat , mat, Imgproc.COLOR_RGB2GRAY);
        Imgproc.cvtColor(mat , mat, Imgproc.COLOR_GRAY2BGRA,4);
        // cv::Mat -> buffer
        byte[] bytes = new byte[ mat.rows() * mat.cols() * mat.channels() ];
        mat.get(0,0, bytes);
        buffer.put(bytes);
*/
        GLES20.glTexSubImage2D(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0, 0, 0,
                width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private void sendImageDatatoNative() {
        int width = mPreviewSize.getWidth();
        int height = mPreviewSize.getHeight();
        Log.v(TAG, "MainRenderer.sendImageDatatoNative.width = " + width);
        Log.v(TAG, "MainRenderer.sendImageDatatoNative.height = " + height);

        final ByteBuffer buffer = ByteBuffer.allocate(width * height * 4);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // buffer -> cv::Mat
        mInputFrame = new Mat(height, width, CvType.CV_8UC4);
        mInputFrame.put(0, 0, buffer.array());
        mSecondFrame = new Mat(height, width, CvType.CV_8UC4);
        mSecondFrame.put(0, 0, buffer.array());

        if (isCalcOpticalFlowFarneback == false) {
            mFirstFrame = new Mat(height, width, CvType.CV_8UC4);
            mFirstFrame.put(0, 0, buffer.array());
            mCalcOpticalFlowCount = 0;
            isCalcOpticalFlowFarneback = true;
        } else {
            if (mCalcOpticalFlowCount > 10) {
                 CalcOpticalFlowFarneback(mSecondFrame, mFirstFrame);
                // cv::Mat -> buffer
                byte[] bytes = new byte[ mInputFrame.rows() * mInputFrame.cols() * mInputFrame.channels() ];
                mInputFrame.get(0,0, bytes);
                // カメラ画像をNDkへ送る。
                sendImageData(bytes, width, height, 4);
                isCalcOpticalFlowFarneback = false;
            } else {
                mCalcOpticalFlowCount++;
            }
        }
        buffer.clear();
    }

    // オプティカルフロー
    private void CalcOpticalFlowFarneback(Mat second_frame, Mat first_frame) {
        org.opencv.core.Point pt1=new org.opencv.core.Point();
        org.opencv.core.Point pt2=new org.opencv.core.Point();
        Scalar color;

        int w = 600;
        int h = 600;
        int posx = (second_frame.cols()/2) - (w/2);
        int posy = (second_frame.rows()/2) - (h/2);
        Rect roiRect = new Rect(posx, posy, h, w);

        Imgproc.cvtColor(first_frame, first_frame, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.cvtColor(second_frame, second_frame,Imgproc.COLOR_BGR2GRAY);

        Mat flow = new Mat(roiRect.size(), CvType.CV_32FC2);
        Video.calcOpticalFlowFarneback(new Mat(first_frame, roiRect),
                                       new Mat(second_frame, roiRect),
                                       flow,0.5,3, 15, 3, 5, 1.1,0);

        for ( int i=0;i < roiRect.size().height;i+=20 ){
            for ( int j=0;j < roiRect.size().width;j+=20 ){
                pt1.x = j;
                pt1.y = i;
                pt2.x = j + flow.get(i,j)[0];
                pt2.y = i + flow.get(i,j)[1];
                color = new Scalar(255,255,0,255);
                org.opencv.imgproc.Imgproc.line(mInputFrame, pt1, pt2, color, 2, 8, 0);
            }
        }
    }

    private void initTex() {
        hTex = new int[1];
        GLES20.glGenTextures(1, hTex, 0);
        mTextureID = hTex[0];
        Log.v(TAG, "MainRenderer.initTex.hTex = " + mTextureID);
        GLES20.glActiveTexture(mTextureID);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
    }

    private static int loadShader ( String vss, String fss ) {
        int vshader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vshader, vss);
        GLES20.glCompileShader(vshader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(vshader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile vshader");
            Log.v("Shader", "Could not compile vshader:" + GLES20.glGetShaderInfoLog(vshader));
            GLES20.glDeleteShader(vshader);
            vshader = 0;
        }

        int fshader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fshader, fss);
        GLES20.glCompileShader(fshader);
        GLES20.glGetShaderiv(fshader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e("Shader", "Could not compile fshader");
            Log.v("Shader", "Could not compile fshader:" + GLES20.glGetShaderInfoLog(fshader));
            GLES20.glDeleteShader(fshader);
            fshader = 0;
        }

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vshader);
        GLES20.glAttachShader(program, fshader);
        GLES20.glLinkProgram(program);

        return program;
    }

    void cacPreviewSize( final int width, final int height ) {
        CameraManager manager = (CameraManager)mView.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraID : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                    continue;

                mCameraID = cameraID;
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                for ( Size psize : map.getOutputSizes(SurfaceTexture.class)) {
                    if ( width == psize.getWidth() && height == psize.getHeight() ) {
                        //mPreviewSize = psize;
                        break;
                    }
                }
                break;
            }
        } catch ( CameraAccessException e ) {
            Log.e(TAG, "cacPreviewSize - Camera Access Exception");
        } catch ( IllegalArgumentException e ) {
            Log.e(TAG, "cacPreviewSize - Illegal Argument Exception");
        } catch ( SecurityException e ) {
            Log.e(TAG, "cacPreviewSize - Security Exception");
        }
        Log.v(TAG, "MainRenderer.mPreviewSize.width " + mPreviewSize.getWidth());
        Log.v(TAG, "MainRenderer.mPreviewSize.height " + mPreviewSize.getHeight());
    }

    void openCamera() {
        Log.v(TAG, "MainRenderer.openCamera");
        CameraManager manager = (CameraManager)mView.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraID);
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mCameraSize = map.getOutputSizes(SurfaceTexture.class)[0];
            manager.openCamera(mCameraID, mStateCallback, mBackgroundHandler);

        } catch ( CameraAccessException e ) {
            Log.e(TAG, "OpenCamera - Camera Access Exception");
        } catch ( IllegalArgumentException e ) {
            Log.e(TAG, "OpenCamera - Camera IllegalArgument Exception\"");
        } catch ( SecurityException e ) {
            Log.e(TAG, "OpenCamera - Security Exception");
        } catch ( InterruptedException e ) {
            Log.e(TAG, "OpenCamera - Interrupted Exception");
        }
    }

    private void closeCamera() {
        Log.v(TAG, "MainRenderer.closeCamera");
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

    };

    // カメラ画像の取得の準備
    private void createCameraPreviewSession() {
        //Log.v(TAG, "MainRenderer.createCameraPreviewSession");
        mSTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        mSurface = new Surface(mSTexture);
        mHandler = new Handler();
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(mSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice)
                                return;

                            mCaptureSession = cameraCaptureSession;
                            try {
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "createCaptureSession");
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        setCameraRotation();
        mInitialized = true;

        // フラッシュライト制御タスク作成
        mFlash = false;
        final CountDown countDown = new CountDown(180000, 1);
        // 開始
        countDown.start();
    }

    public void setCameraRotation() {
        if (!mInitialized) {
            return;
        }

        android.graphics.Point displaySize = new android.graphics.Point();
        mActivity.getWindowManager().getDefaultDisplay().getSize(displaySize);

        if (displaySize.x > displaySize.y) {
            double scale = (double) displaySize.y / (double) mCameraSize.getHeight();
            mImageSize = new Size((int)(scale * mCameraSize.getWidth()), (int)(scale * mCameraSize.getHeight()));
        } else {
            double scale = (double) displaySize.x / (double) mCameraSize.getHeight();
            mImageSize = new Size((int)(scale * mCameraSize.getHeight()), (int)(scale * mCameraSize.getWidth()));
        }

        int orientation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        switch(orientation) {
            case Surface.ROTATION_0:
                mCameraRotation = mIsPortraitDevice ? CameraRotation.ROTATION_0 : CameraRotation.ROTATION_270;
                Log.e(TAG, "setCameraRotation 0 " + mCameraRotation);
                break;
            case Surface.ROTATION_90:
                mCameraRotation = mIsPortraitDevice ? CameraRotation.ROTATION_90 : CameraRotation.ROTATION_0;
                Log.e(TAG, "setCameraRotation 90" + mCameraRotation);
                break;
            case Surface.ROTATION_180:
                mCameraRotation = mIsPortraitDevice ? CameraRotation.ROTATION_180 : CameraRotation.ROTATION_90;
                Log.e(TAG, "setCameraRotation 180" + mCameraRotation);
                break;
            case Surface.ROTATION_270:
                mCameraRotation = mIsPortraitDevice ? CameraRotation.ROTATION_270 : CameraRotation.ROTATION_180;
                Log.e(TAG, "setCameraRotation 270" + mCameraRotation);
                break;
        }
    }

    private void startBackgroundThread() {
        if(mBackgroundThread == null) {
            mBackgroundThread = new HandlerThread("CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(TAG, "stopBackgroundThread");
        }
    }

    private Bitmap getBitmap() {
        int orientation = -90;
        boolean mirror = true;
        int width = mPreviewSize.getWidth();
        int height = mPreviewSize.getHeight();
        width = 1024;
        height = 1024;

        final int[] pixels = new int[width * height];
        final IntBuffer buffer = IntBuffer.wrap(pixels);
        buffer.position(0);

        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        //Log.v(TAG, "MainRenderer.getBitmap");
        return createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888, orientation, mirror);
    }

    private static final Bitmap createBitmap(final int[] pixels, final int width, final int height, final Bitmap.Config config, final int orientation, final boolean mirror) {
        // 取得したピクセルデータは R (赤) と B (青) が逆になっています。
        // また垂直方向も逆になっているので以下のように ColorMatrix と Matrix を使用して修正します。

		/*
		 * カラーチャネルを交換するために ColorMatrix と ColorMatrixFilter を使用します。
		 *
		 * 5x4 のマトリックス: [
		 *   a, b, c, d, e,
		 *   f, g, h, i, j,
		 *   k, l, m, n, o,
		 *   p, q, r, s, t
		 * ]
		 *
		 * RGBA カラーへ適用する場合、以下のように計算します:
		 *
		 * R' = a * R + b * G + c * B + d * A + e;
		 * G' = f * R + g * G + h * B + i * A + j;
		 * B' = k * R + l * G + m * B + n * A + o;
		 * A' = p * R + q * G + r * B + s * A + t;
		 *
		 * R (赤) と B (青) を交換したいので以下の様になります。
		 *
		 * R' = B => 0, 0, 1, 0, 0
		 * G' = G => 0, 1, 0, 0, 0
		 * B' = R => 1, 0, 0, 0, 0
		 * A' = A => 0, 0, 0, 1, 0
		 */
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        // R (赤) と B (青) が逆なので交換します。
        paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                0, 0, 1, 0, 0,
                0, 1, 0, 0, 0,
                1, 0, 0, 0, 0,
                0, 0, 0, 1, 0
        })));

        final Bitmap bitmap;
        final int diff;
        if ((orientation % 180) == 0) {
            bitmap = Bitmap.createBitmap(width, height, config);
            diff = 0;
        } else {
            bitmap = Bitmap.createBitmap(height, width, config);
            diff = (width - height) / 2;
        }
        final Canvas canvas = new Canvas(bitmap);

        final Matrix matrix = new Matrix();
        // 上下が逆さまなので垂直方向に反転させます。
        matrix.postScale(mirror ? -1.0f : 1.0f, -1.0f, width / 2, height / 2);
        // 傾きを付けます。
        matrix.postRotate(-orientation, width / 2, height / 2);
        if (diff != 0) {
            // 垂直方向の場合は回転による座標のずれを修正します。
            matrix.postTranslate(-diff, diff);
        }

        canvas.concat(matrix);

        // 描画します。
        canvas.drawBitmap(pixels, 0, width, 0, 0, width, height, false, paint);

        return bitmap;
    }

    private void saveBitmapToSd(Bitmap bmap) {
        // 日付でファイル名を作成
        Date mDate = new Date();
        SimpleDateFormat fileName = new SimpleDateFormat("yyyyMMdd_HHmmss");
        String fname = fileName.format(mDate) + ".jpg";
        boolean ret = false;
        try {
            ret = saveJpegSDCard(fname, bmap, 100);
        } catch (IOException e) {
            Log.v(TAG, "saveBitmapToSd1 : " + e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.v(TAG, "saveBitmapToSd1 : " + e.getMessage());
        }
    }

    private static final String DEFAULT_ENCORDING = "UTF-8"; //デフォルトのエンコード
    // SDCard のマウント状態をチェックする(Android 用)
    public static final boolean isMountSDCard() {
        final String state = Environment.getExternalStorageState();
        if (state.equals(Environment.MEDIA_MOUNTED)) {
            return true;   //マウントされている
        } else {
            return false;  //マウントされていない
        }
    }

    // SDCard のルートディレクトリを取得(Android 用)
    public static final File getSDCardDir() {
        return Environment.getExternalStorageDirectory();
    }

    // SDCard 内の絶対パスに変換(Android 用)
    public static final String toSDCardAbsolutePath(String fileName) {
        return getSDCardDir().getAbsolutePath() + File.separator + fileName;
    }

    //SDCard に、画像ファイルを保存する(jpg) (Android 用)
    //<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" /> が必要。
    public static final boolean saveJpegSDCard(String fileName, Bitmap bitmap, int quality)
            throws IOException, IllegalArgumentException {

        if (!isMountSDCard()) {
            throw new IOException("No Mount");
        }
        BufferedOutputStream bos = null;
        Bitmap tmp = null;
        boolean ret = false;
        try {
            bos = new BufferedOutputStream(new FileOutputStream(toSDCardAbsolutePath(fileName)));
            tmp = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            ret = tmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        } finally {
            if (tmp != null) {
                tmp.recycle();
                tmp = null;
            }
            try {
                bos.close();
            } catch (Exception e) {
                //IOException, NullPointerException
            }
        }
        return ret;
    }

    class CountDown extends CountDownTimer {
        public CountDown(long millisInFuture, long countDownInterval) {
            super(millisInFuture, countDownInterval);
        }
        @Override
        public void onFinish() {
            try {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                mFlash = false;
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            Log.v("DEBUG001", "particleScriptTest1.onFinish");
        }
        // インターバルで呼ばれる
        @Override
        public void onTick(long millisUntilFinished) {
            //Log.v("DEBUG001", "particleScriptTest1.onTick1");
            try {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                if (mFlash == false) {
                    mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                    mFlash = true;
                } else {
                    mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                    mFlash = false;
                }
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, mBackgroundHandler);
            } catch (CameraAccessException e) {
                //Log.v("DEBUG001", e.getMessage().toString());
                e.printStackTrace();
            }
            //Log.v("DEBUG001", "particleScriptTest1.onTick2");
        }

    }

}

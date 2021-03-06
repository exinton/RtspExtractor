package me.courbiere.rtspextractor;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.MediaFormatHolder;
import com.google.android.exoplayer.SampleHolder;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.TrackInfo;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.source.SampleExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RtspExtractor implements SampleExtractor {
    public static final String TAG = RtspExtractor.class.getSimpleName();

    static {
        System.loadLibrary("rtspextractor");
        ffInit();
    }

    private Context mContext;
    private Uri mUri;
    private Map<String, String> mHeaders;
    private TrackInfo[] mTrackInfos;
    private boolean mIsPrepared;

    private long pFormatCtx = -1; // AVFormatContext

    public RtspExtractor(Context context, Uri uri, Map<String, String> headers) {
        mContext = context;
        mUri = uri;
        mHeaders = headers;
        mIsPrepared = false;
    }

    @Override
    public boolean prepare() throws IOException {
        if (mIsPrepared) {
            return true;
        }

        pFormatCtx = ffOpenInput(mUri.toString());

        if (pFormatCtx == -1) {
            throw new IOException("Unable to prepare stream, pointer: " + pFormatCtx);
        }

        mTrackInfos = ffGetTrackInfos(pFormatCtx);
        for (int i = 0; i < mTrackInfos.length; i++) {
            deselectTrack(i);
        }

        mIsPrepared = true;

        return true;
    }

    @Override
    public TrackInfo[] getTrackInfos() {
        return mTrackInfos;
    }

    @Override
    public void selectTrack(int index) {
        ffSelectTrack(pFormatCtx, index);
    }

    @Override
    public void deselectTrack(int index) {
        ffDeselectTrack(pFormatCtx, index);
    }

    @Override
    public long getBufferedPositionUs() {
        // TODO: Get this from FFMPEG
        return TrackRenderer.UNKNOWN_TIME_US;
    }

    @Override
    public void seekTo(long positionUs) {
        ffSeekTo(pFormatCtx, positionUs);
    }

    @Override
    public void getTrackMediaFormat(int track, MediaFormatHolder mediaFormatHolder) {
        int height = ffGetHeight(pFormatCtx, track);
        int width = ffGetWidth(pFormatCtx, track);
        //int bitrate = ffGetBitrate(pFormatCtx, track);
        //int channels = ffGetChannelCount(pFormatCtx, track);
        //int sampleRate = ffGetSampleRate(pFormatCtx, track);
        byte[] initData = ffGetInitData(pFormatCtx, track);
        List<byte[]> initList = new ArrayList<byte[]>();

        // Split SPS/PPS data by start code and insert into initialization data
        int i, start;
        for (i = 4, start = 0; i < initData.length; i++) {

            // Found a new start code - add the last byte array to the list
            if (i+4 <= initData.length && initData[i] == 0 && initData[i+1] == 0 && initData[i+2] == 0 && initData[i+3] == 1) {
                byte[] csd = new byte[i-start];
                System.arraycopy(initData, start, csd, 0, i-start);
                initList.add(csd);
                Log.d(TAG, "inserted csd " + csd.length);
                start = i;
            }
        }

        // Insert the last csd
        if (i > start) {
            byte[] csd = new byte[i-start];
            System.arraycopy(initData, start, csd, 0, i-start);
            initList.add(csd);
            Log.d(TAG, "inserted final csd " + csd.length);
        }

        MediaFormat format = MediaFormat.createVideoFormat(mTrackInfos[track].mimeType, 10 * 1024 * 1024, width, height, 1, initList);
        mediaFormatHolder.format = format;

        // Start playing the stream
        //ffPlay(pFormatCtx);
    }

    @Override
    public int readSample(int track, SampleHolder sampleHolder) throws IOException {
        if (sampleHolder.data == null) {
            sampleHolder.size = 0;
            return SampleSource.SAMPLE_READ;
        }

        if (!ffReadSample(pFormatCtx, sampleHolder)) {
            return SampleSource.NOTHING_READ;
        }

        //Log.d(TAG, "PTS: " + sampleHolder.timeUs);
        //Log.d(TAG, "frame start: " + sampleHolder.data.getInt(1));

        return SampleSource.SAMPLE_READ;
    }

    @Override
    public void release() {
        ffCloseInput(pFormatCtx);
        pFormatCtx = 0;
        ffDeinit();
    }

    /* Native methods */

    private static native void ffInit();
    private static native void ffDeinit();
    private static native long ffOpenInput(String uri);
    private static native void ffCloseInput(long pFormatCtx);
    private static native TrackInfo[] ffGetTrackInfos(long pFormatCtx);
    private static native void ffSelectTrack(long pFormatCtx, int index);
    private static native void ffDeselectTrack(long pFormatCtx, int index);
    private static native void ffSeekTo(long pFormatCtx, long positionUs);
    private static native int ffGetHeight(long pFormatCtx, int index);
    private static native int ffGetWidth(long pFormatCtx, int index);
    private static native int ffGetBitrate(long pFormatCtx, int index);
    private static native int ffGetChannelCount(long pFormatCtx, int index);
    private static native int ffGetSampleRate(long pFormatCtx, int index);
    private static native byte[] ffGetInitData(long pFormatCtx, int index);
    private static native void ffPlay(long pFormatCtx);
    private static native int ffTest(String uri);
    private static native boolean ffReadSample(long pFormatCtx, SampleHolder sampleHolder);
}

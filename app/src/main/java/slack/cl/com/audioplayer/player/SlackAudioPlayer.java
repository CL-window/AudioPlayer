package slack.cl.com.audioplayer.player;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import slack.cl.com.audioplayer.PrecisionUtil;

/**
 * Created by slack
 * on 17/11/1 下午7:15
 */

public class SlackAudioPlayer implements IMediaPlayer {

    /**
     * 暂时保存 解码出来的时间，
     * key: 解码出来的时间戳，时间精度
     * value: 当前这个时间精度下，第一个解码出来的时间戳在缓存文件中的位置
     */
    private final Map<String, Long> mTimeMap = new HashMap<>();

    private final static float DEFAULT_TIME_PRECISION = 1000000.0f; // 1000 ms
    private final static int QUEUE_TIME_OUT = 1000000; // 1000 ms
    private final static int DEQUE_TIME_OUT = 5000; // 5 ms
    private String mMusicFilePath;
    private final RandomAccessFile mCacheAccessFile;

    private boolean mLoop;
    private float mLeftVolume = 1.0f, mRightVolume = 1.0f;
    private boolean mExitFlag;
    private boolean mIsPlaying;
    private int mSampleRate = 41100, mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private final static int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final static int mChannelConfig = AudioFormat.CHANNEL_OUT_STEREO;
    private final Object mLock = new Object();

    private ByteBuffer mTempMusicBuffer;

    /**
     * 用户选择的是时长，这个需要换一下，时长和文件长度的一个转化！！！
     */
    private int mStartPlayIndex = 0;
    private long mEndPlayIndex = Long.MAX_VALUE;
    /**
     * 当前播放到的 在缓存文件中的位置
     */
    private long mCurrentReadIndex;

    /**
     * 读取的文件的总的长度
     */
    private long mSumDecodeDataLength;
    /**
     * 需要  1.解码音乐的线程 2.播放音乐的线程
     */
    private final Thread mDecodeThread;
    private final HandlerThread mPlayThread;
    private final Handler mPlayHandler;

    private AudioTrack mAudioTrack;
    private int mBufferSize = 4096;

    private boolean mHasEndOfStream;


    private OnPreparedListener mOnPreparedListener;
    private OnCompletionListener mOnCompletionListener;
    private OnSeekCompleteListener mOnSeekCompleteListener;
    private OnMusicDurationListener mOnMusicDurationListener;

    public SlackAudioPlayer(@NonNull Context context) throws Exception {
        File cache = new File(context.getCacheDir(), "cache.temp");
        cache.deleteOnExit();
        mCacheAccessFile = new RandomAccessFile(cache, "rw");
        mPlayThread = new HandlerThread("Play_" + System.currentTimeMillis());
        mPlayThread.start();
        mDecodeThread = new Thread(mDecodeRunnable);
        mPlayHandler = new Handler(mPlayThread.getLooper());
    }

    @Override
    public void setDataSource(String path) {
        mMusicFilePath = path;
    }

    @Override
    public void setLooping(boolean looping) {
        mLoop = looping;
    }

    @Override
    public void setVolume(float left, float right) {
        mLeftVolume = left;
        mRightVolume = right;
    }

    @Override
    public boolean isPlaying() {
        return mIsPlaying;
    }

    @Override
    public void prepareAsync() throws IOException {
        if(mMusicFilePath == null) {
            throw new IOException("Please setDataSource first");
        }

        mDecodeThread.start();
    }

    private Runnable mDecodeRunnable = new Runnable() {
        @Override
        public void run() {

            try {
                prepareInternal();
                decodeInternal(extractor, decoder);
            }
            catch (Exception e) {
                e.printStackTrace();
            }

            releaseInternal(extractor, decoder);
        }

        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        private void prepareInternal() {
            mHasEndOfStream = false;
            try {
                extractor = new MediaExtractor();
                extractor.setDataSource(mMusicFilePath);

                MediaFormat audioFormat = null;
                int trackCount = extractor.getTrackCount();
                for (int i = 0; i < trackCount; i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    Log.e("slack", "Audio MediaFormat : " + format);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime.startsWith("audio/")) {
                        audioFormat = format;
                        extractor.selectTrack(i);
                        break;
                    }
                }

                if (audioFormat == null) {
                    throw new Exception("No Audio Track Found in : " + mMusicFilePath);
                }


                obtainMusicDuration(audioFormat);

                mSampleRate = getIntegerFormat(audioFormat, MediaFormat.KEY_SAMPLE_RATE, 44100);
                mChannelCount = getIntegerFormat(audioFormat, MediaFormat.KEY_CHANNEL_COUNT, AudioFormat.CHANNEL_IN_MONO);

                String mime = audioFormat.getString(MediaFormat.KEY_MIME);
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(audioFormat, null, null, 0);
                decoder.start();

            } catch (Exception e) {
                e.printStackTrace();

                try {
                    if (extractor != null) {
                        extractor.release();
                    }

                    if (decoder != null) {
                        decoder.release();
                    }
                } catch (Exception ee) {
                    e.printStackTrace();
                }
            }


            mBufferSize = AudioTrack.getMinBufferSize(mSampleRate,
                    mChannelConfig, mAudioFormat);
            mTempMusicBuffer = ByteBuffer.allocate(mBufferSize).order(ByteOrder.nativeOrder());

            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                    mSampleRate, mChannelConfig, mAudioFormat, mBufferSize,
                    AudioTrack.MODE_STREAM);
            mAudioTrack.setStereoVolume(mLeftVolume, mRightVolume);//设置当前音量大小
        }

    };

    private int getIntegerFormat(MediaFormat format, String key, int defaultValue) {
        if(format.containsKey(key)) {
            return format.getInteger(key);
        }
        return defaultValue;
    }

    private void decodeInternal(MediaExtractor extractor,MediaCodec decoder) {
        mExitFlag = false;
        boolean endOfStream = false;
        ByteBuffer[] inputBuffers = decoder.getInputBuffers();
        while (!mExitFlag) {
            /**
             * queue input buffer
             */
            int inputIndex = decoder.dequeueInputBuffer(QUEUE_TIME_OUT);
            if (inputIndex >= 0) {
                ByteBuffer buffer = inputBuffers[inputIndex];
                buffer.clear();

                int size = extractor.readSampleData(buffer, 0);
                if (size > 0) {
                    decoder.queueInputBuffer(inputIndex, 0, size, extractor.getSampleTime(), extractor.getSampleFlags());
                    extractor.advance();
                }
                else {
                    endOfStream = true;
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }

            /**
             * dequeue output buffer
             */
            dequeueOutputBuffers(decoder, endOfStream);

            if (endOfStream) {
                break;
            }
        }
        mHasEndOfStream = true;
    }

    private void dequeueOutputBuffers(MediaCodec decoder, boolean endOfStream) {
        int maxEndOfStreamTryCount = 10;

        ByteBuffer [] outputBuffers = decoder.getOutputBuffers();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (!mExitFlag) {
            int index = decoder.dequeueOutputBuffer(bufferInfo, DEQUE_TIME_OUT);
            if (index >= 0) {
                ByteBuffer buffer = outputBuffers[index];
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) > 0) {
                    bufferInfo.size = 0;
                }

                /**
                 * 写入到 cache 文件
                 */
                if (bufferInfo.size > 0) {
                    writeAudioDataToCache(buffer, bufferInfo);
                }

                decoder.releaseOutputBuffer(index, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) > 0) {
                    Log.e("slack", "Music Decoder End Of Stream");
                    break;
                }
            }
            else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = decoder.getOutputBuffers();
            }
            else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                MediaFormat format = decoder.getOutputFormat();

                Log.e("slack", "Music Decoder output format: " + format);
            }
            else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (endOfStream) {
                    Log.e("slack", "Waiting Music Decoder finish!");
                    try {
                        Thread.sleep(10);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                    maxEndOfStreamTryCount -= 1;
                    if (maxEndOfStreamTryCount <= 0) {
                        Log.e("slack", "Waiting Music Decoder finish timeout, break");
                        break;
                    }
                }
                else {
                    break;
                }
            }
            else {
                Log.e("slack", "Unknown Music decoder output index: " + index);
                break;
            }
        }
    }

    private void writeAudioDataToCache(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        Log.e("slack", "Buffer Capacity: " + buffer.capacity() + "  Info Size: " + info.size +
                " TimeUs: " + info.presentationTimeUs + " SumData: " + mSumDecodeDataLength);
        ByteBuffer copyBuffer = ByteBuffer.allocate(info.size).order(ByteOrder.nativeOrder());
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
        copyBuffer.position(0);
        copyBuffer.put(buffer);
        copyBuffer.position(0);
        try {
            mCacheAccessFile.seek(mSumDecodeDataLength);
            mCacheAccessFile.write(copyBuffer.array(), 0, info.size);

            String key = PrecisionUtil.formTextByPrecision(info.presentationTimeUs/DEFAULT_TIME_PRECISION);
            synchronized (mLock) {
                if(!mTimeMap.containsKey(key)) {
                    mTimeMap.put(key, mSumDecodeDataLength);
                }
            }
            Log.i("slack", "Key: " + key);
            mSumDecodeDataLength += info.size;

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * duration : 单位 微妙
     * 返回单位 秒
     */
    private void obtainMusicDuration(MediaFormat audioFormat) {
        long duration = audioFormat.getLong(MediaFormat.KEY_DURATION);
        float time = duration / DEFAULT_TIME_PRECISION;
        if(mOnMusicDurationListener != null) {
            mOnMusicDurationListener.onMusicDuration(this, time);
        }
    }

    private void releaseInternal(MediaExtractor extractor, MediaCodec decoder) {
        try {
            if (extractor != null) {
                extractor.release();
            }

            if (decoder != null) {
                decoder.release();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void start() {
        mIsPlaying = true;
        mPlayHandler.post(mPlayRunnable);
    }

    private Runnable mPlayRunnable = new Runnable() {
        @Override
        public void run() {
            try {

                mAudioTrack.play();
                while (mIsPlaying) {
                    getAudioData(mBufferSize);
                    if (mIsPlaying) {
                        Log.e("slack", "playing...");
                        byte[] data = mTempMusicBuffer.array();
                        mAudioTrack.write(data, 0, data.length);
                    } else {
                        if(mOnCompletionListener != null) {
                            mOnCompletionListener.onCompletion(SlackAudioPlayer.this);
                        }
                    }
                }

                mAudioTrack.stop();
                Log.e("slack", "playing finish...");
            } catch (Exception e) {
                Log.e("slack", "error:" + e.getMessage());
            }
        }

    };

    /**
     * 循环获取音乐数据
     *
     * @return audio data
     */
    ByteBuffer getAudioData(int size) {

        synchronized (mLock) {//记得加锁
            if (mSumDecodeDataLength == 0) {
                Log.e("slack", "Waiting Music Decode product data!");
                try {
                    mLock.wait(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            if (mSumDecodeDataLength == 0) {
                Log.e("slack", "Error No Music Decode data available!");
                return null;
            }

            try {
                byte [] arr = mTempMusicBuffer.array();
                mCacheAccessFile.seek(mCurrentReadIndex);
                long need = mCurrentReadIndex + size;
                // 正常情况，读取的数据是 已解码的，且在选中播放范围内
                long end  =  Math.min(mSumDecodeDataLength, mEndPlayIndex);
                if (need <= end) {
                    mCacheAccessFile.read(arr, 0, size);
                } else {
                    // 不正常情况，
                    if(mHasEndOfStream) {
                        // 已经全部解码完成
                        if (mLoop) {
                            /**
                             * 循环读取, 这里的循环读取是建立在，mSumDecodeDataLength > size 的情况下的
                             */
                            int first = (int) (end - mCurrentReadIndex);
                            mCacheAccessFile.read(arr, mStartPlayIndex, first);
                            mCacheAccessFile.seek(0);
                            int remain = size - first;
                            mCacheAccessFile.read(arr, first, remain);
                        } else {
                            mIsPlaying = false;
                        }
                    } else {
                        // 等待咯
                        /**
                         * 等待数据
                         */
                        try {
                            mLock.wait(1000);
                        }
                        catch (Exception e) {
                            e.printStackTrace();
                        }

                        if (need <= end) {
                            mCacheAccessFile.read(arr);
                        }
                        else {
                            if (mHasEndOfStream) {
                                /**
                                 * 循环读取
                                 */
                                int first = (int) (mSumDecodeDataLength - mCurrentReadIndex);
                                mCacheAccessFile.read(arr, 0, first);
                                mCacheAccessFile.seek(0);
                                int remain = size - first;
                                mCacheAccessFile.read(arr, first, remain);
                            }
                            else {
                                /**
                                 * 如果还没读到，返回null
                                 */
                                Log.e("slack", "Error no enough data!");
                                return null;
                            }
                        }
                    }
                }

                mCurrentReadIndex = (mCurrentReadIndex + size) % mSumDecodeDataLength;
                return mTempMusicBuffer;
            }
            catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    /**
     * 如果当前在播放，先暂停，调整完成后恢复播放
     * 这里做了限制，只处理用户滑动完成时 ，滑动过程中不处理
     */
    @Override
    public void updateRange(float start, float end) {
        Log.i("slack", "updateRange， start: " + start + ", end: " + end);
        boolean playing = mIsPlaying;
        pause();
        synchronized (mLock) {
            String endKey = PrecisionUtil.formTextByPrecision(end);
            if(mTimeMap.containsKey(endKey)) {
                // 解码完成了，
                mEndPlayIndex = mTimeMap.get(endKey);
            }
            String startKey = PrecisionUtil.formTextByPrecision(start);
            if(mTimeMap.containsKey(endKey)) {
                // 解码完成了，
                mCurrentReadIndex = mTimeMap.get(startKey);
                mStartPlayIndex = (int) mCurrentReadIndex;
            } else {
                // 用户选取的开始播放的点还没有解析完成，这个就麻烦了，要么让用户等着，要么跳跃着解析音乐
                try {
                    mLock.wait(1000);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }

                startKey = PrecisionUtil.formTextByPrecision(start);
                if(mTimeMap.containsKey(endKey)) {
                    // 解码完成了，
                    mCurrentReadIndex = mTimeMap.get(startKey);
                    mStartPlayIndex = (int) mCurrentReadIndex;
                } else {
                    mStartPlayIndex = 0;
                    mCurrentReadIndex = 0;
                }
            }
        }
        if(playing) {
            start();
        }

        Log.i("slack", "updateRange， start: " + start + ", end: " + end + " ,CurrentTime:" + mCurrentReadIndex + " ,EndIndex:" + mEndPlayIndex);
    }

    @Override
    public void pause() {
        synchronized (mLock) {
            mIsPlaying = false;
            mAudioTrack.stop();
            Log.e("slack", "pause...");
        }
    }

    @Override
    public void reset() {

    }

    @Override
    public void seekTo(int timestamp) {

    }

    @Override
    public long getCurrentPosition() {
        return (long) mCurrentReadIndex;
    }

    @Override
    public void release() {
        try {
            mExitFlag = true;
            mCacheAccessFile.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        mTimeMap.clear();
        mAudioTrack.stop();
        mAudioTrack.release();
        mPlayThread.quitSafely();
        mDecodeThread.interrupt();
    }

    @Override
    public void setOnPreparedListener(OnPreparedListener listener) {
        mOnPreparedListener = listener;
    }

    @Override
    public void setOnCompletionListener(OnCompletionListener listener) {
        mOnCompletionListener = listener;
    }

    @Override
    public void setOnSeekCompleteListener(OnSeekCompleteListener listener) {
        mOnSeekCompleteListener = listener;
    }

    @Override
    public void setOnMusicDurationListener(OnMusicDurationListener listener) {
        mOnMusicDurationListener = listener;
    }

}

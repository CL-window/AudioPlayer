package slack.cl.com.audioplayer.player;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by slack
 * on 17/11/1 下午7:15
 */

public class SlackAudioPlayer implements IMediaPlayer {

    private final static int QUEUE_TIME_OUT = 1000000; // 1000 ms
    private final static int DEQUE_TIME_OUT = 5000; // 5 ms
    private String mMusicFilePath;
    private final RandomAccessFile mCacheAccessFile;

    private boolean mLoop;
    private float mLeftVolume = 1.0f, mRightVolume = 1.0f;
    private float mStartPosition, mEndPisition, mCurrentPosition;
    private boolean mExitFlag;
    private boolean mIsPlaying;
    private int mSampleRate = 41100, mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private final static int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final static int mChannelConfig = AudioFormat.CHANNEL_OUT_STEREO;
    private final Object mLock = new Object();

    private ByteBuffer mTempMusicBuffer;

    private int mCurrentReadIndex;
    private int mCurrentReadSize;
    /**
     * 需要  1.解码音乐的线程 2.播放音乐的线程
     */
    private ExecutorService mThreadPool = Executors.newCachedThreadPool();

    /**
     * 总的长度
     */
    private int mSumDecodeDataLength;

    private OnPreparedListener mOnPreparedListener;
    private OnCompletionListener mOnCompletionListener;
    private OnSeekCompleteListener mOnSeekCompleteListener;
    private OnMusicDurationListener mOnMusicDurationListener;

    public SlackAudioPlayer(@NonNull Context context) throws Exception {
        File cache = new File(context.getCacheDir(), "cache.temp");
        cache.deleteOnExit();
        mCacheAccessFile = new RandomAccessFile(cache, "rw");
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

        // TODO: 17/11/3 test
        mSumDecodeDataLength = 10000000;
//        mThreadPool.execute(mDecodeRunnable);
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
        Log.e("slack", "Buffer Capacity: " + buffer.capacity() + "  Info Size: " + info.size);
        ByteBuffer copyBuffer = ByteBuffer.allocate(info.size).order(ByteOrder.nativeOrder());
        buffer.position(info.offset);
        buffer.limit(info.offset + info.size);
        copyBuffer.position(0);
        copyBuffer.put(buffer);
        copyBuffer.position(0);
        try {
            mCacheAccessFile.seek(mSumDecodeDataLength);
            mCacheAccessFile.write(copyBuffer.array(), 0, info.size);
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
        float time = duration / 1000000f;
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
        mThreadPool.execute(mPlayRunnable);
    }

    private Runnable mPlayRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                int bufferSize = AudioTrack.getMinBufferSize(mSampleRate,
                        mChannelConfig, mAudioFormat);
                mTempMusicBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.nativeOrder());

                AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC,
                        mSampleRate, mChannelConfig, mAudioFormat, bufferSize,
                        AudioTrack.MODE_STREAM);
                track.setStereoVolume(mLeftVolume, mRightVolume);//设置当前音量大小
                // 开始播放
                track.play();
                // 由于AudioTrack播放的是流，所以，我们需要一边播放一边读取
                while (mIsPlaying) {
                    getAudioData(bufferSize);
                    if (mIsPlaying) {
                        Log.e("slack", "playing...");
                        byte[] data = mTempMusicBuffer.array();
                        // 然后将数据写入到AudioTrack中
                        track.write(data, 0, data.length);
                    }
                }

                // 播放结束
                track.stop();
            } catch (Exception e) {
                // TODO: handle exception
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
//            if (mSumDecodeDataLength == 0) {
//                Log.e("slack", "Waiting Music Decode product data!");
//                try {
//                    mLock.wait(1000);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
//
//            if (mSumDecodeDataLength == 0) {
//                Log.e("slack", "Error No Music Decode data available!");
//                return null;
//            }

            try {
                byte [] arr = mTempMusicBuffer.array();
                mCacheAccessFile.seek(mCurrentReadIndex);
                if (mCurrentReadIndex + size <= mSumDecodeDataLength) {
                    mCacheAccessFile.read(arr, 0, size);
                } else {
                    if (mLoop) {
                        /**
                         * 循环读取, 这里的循环读取是建立在，mSumDecodeDataLength > size 的情况下的
                         */
                        int first = mSumDecodeDataLength - mCurrentReadIndex;
                        mCacheAccessFile.read(arr, 0, first);
                        mCacheAccessFile.seek(0);
                        int remain = size - first;
                        mCacheAccessFile.read(arr, first, remain);
                    } else {
                        mIsPlaying = false;
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

    @Override
    public void updateRange(float start, float end) {
        mStartPosition = start;
        mEndPisition = end;
        if(end < start) {
            mEndPisition = start;
        }
        if(mCurrentPosition < start) {
            mCurrentPosition = start;
        }
    }

    @Override
    public void pause() {
        mIsPlaying = false;
    }

    @Override
    public void reset() {

    }

    @Override
    public void seekTo(int timestamp) {

    }

    @Override
    public long getCurrentPosition() {
        return (long) mCurrentPosition;
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

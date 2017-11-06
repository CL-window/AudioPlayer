package slack.cl.com.audioplayer.player;

import android.support.annotation.IntDef;
import android.view.Surface;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created by Kejin on 2017/6/26.
 * Copyright © 2016年 Benqu. All rights reserved.
 */

public interface IMediaPlayer {

    void setDataSource(String path);

    void setLooping(boolean looping);

    void setVolume(float left, float right);

    boolean isPlaying();

    void prepareAsync() throws IOException;

    void start();

    void updateRange(float start, float end);

    void pause();

    void reset();

    void seekTo(int timestamp);

    long getCurrentPosition();

    void release();


    interface OnPreparedListener {
        void onPrepared(IMediaPlayer mp);
    }

    void setOnPreparedListener(OnPreparedListener listener);


    interface OnCompletionListener {
        void onCompletion(IMediaPlayer mp);
    }

    void setOnCompletionListener(OnCompletionListener listener);


    interface OnSeekCompleteListener {
        void onSeekComplete(IMediaPlayer mp);
    }

    void setOnSeekCompleteListener(OnSeekCompleteListener listener);

    interface OnMusicDurationListener {
        /**
         * @param duration duration 单位秒
         */
        void onMusicDuration(IMediaPlayer mp, float duration);
    }

    void setOnMusicDurationListener(OnMusicDurationListener listener);

    /**
     * what:
     * 0x001    :   播放部分还没有解码完成，需要用户等待
     */
    int ERROR_NOT_PREPARED = 0x001;
    @IntDef({ERROR_NOT_PREPARED})
    @Retention(RetentionPolicy.SOURCE)
    @interface AudioPlayError {}
    interface OnErrorListener {
        void onError(IMediaPlayer mp, @AudioPlayError int what, String msg);
    }

    void setOnErrorListener(OnErrorListener listener);
}

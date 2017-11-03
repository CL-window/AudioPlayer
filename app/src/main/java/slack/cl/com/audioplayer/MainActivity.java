package slack.cl.com.audioplayer;

import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import java.io.File;

import slack.cl.com.audioplayer.player.IMediaPlayer;
import slack.cl.com.audioplayer.player.SlackAudioPlayer;
import slack.cl.com.audioplayer.widget.RangeSeekBar;

public class MainActivity extends AppCompatActivity {

    RangeSeekBar mRangSeekBar;
    Button mPlayBtn;
    boolean mIsPlaying = false;
    IMediaPlayer mMediaPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mRangSeekBar = (RangeSeekBar) findViewById(R.id.slack_audio_play_seekbar);
        mRangSeekBar.setOnRangeChangedListener(mOnRangeChangedListener);
        mPlayBtn = (Button) findViewById(R.id.slack_audio_play_btn);
        updatePlayInfo();
        initMusicFile();
    }

    /**
     * TODO 这里使用测试 音乐文件，文件路径SD卡根目录，音乐文件name ： test.mp3
     */
    private void initMusicFile() {
        File music = new File(Environment.getExternalStorageDirectory(), "test.mp3");
        try {
            mMediaPlayer = new SlackAudioPlayer(this);
            mMediaPlayer.setDataSource(music.getAbsolutePath());
            mMediaPlayer.setOnMusicDurationListener(mMusicDurationListener);
            mMediaPlayer.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private IMediaPlayer.OnMusicDurationListener mMusicDurationListener = new IMediaPlayer.OnMusicDurationListener() {
        @Override
        public void onMusicDuration(IMediaPlayer mp, final float duration) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mRangSeekBar.setRange(0, duration);
                }
            });
        }
    };

    private RangeSeekBar.OnRangeChangedListener mOnRangeChangedListener = new RangeSeekBar.OnRangeChangedListener() {
        @Override
        public void onRangeChanged(RangeSeekBar view, float min, float max, boolean isFromUser) {
            if(isFromUser) {
                mMediaPlayer.pause();
                mMediaPlayer.updateRange(min, max);
                mMediaPlayer.start();
            }
        }
    };

    private void updatePlayInfo() {
        if(mIsPlaying) {
            mPlayBtn.setText("Stop");
        } else {
            mPlayBtn.setText("Start");
        }
    }

    public void onPlayBtnClick(View view) {
        if(mIsPlaying) {
            mIsPlaying = false;
            mMediaPlayer.pause();
        } else {
            mIsPlaying = true;
            mMediaPlayer.start();
        }
        updatePlayInfo();
    }
}

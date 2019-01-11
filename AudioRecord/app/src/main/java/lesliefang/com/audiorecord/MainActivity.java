package lesliefang.com.audiorecord;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "audio";
    Button btnStartRecord;
    Button btnStopRecord;
    TextView tvState;
    Button btnPlay;
    Button btnConvert2Wav;
    Button btnPlayStream;
    Button btnStopStream;

    final int sampleRateInHz = 44100;
    final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    int audioRecordBufferSize;

    boolean isRecording = false;
    AudioRecord audioRecord;

    File pcmFile; // pcm 保存路径

    AudioTrack audioTrack;
    boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 请开启所有权限，这里没有处理权限回调
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission();
        }
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermission();
        }

        btnStartRecord = findViewById(R.id.start_record);
        btnStopRecord = findViewById(R.id.stop_record);
        tvState = findViewById(R.id.state);
        btnPlay = findViewById(R.id.play);
        btnConvert2Wav = findViewById(R.id.convert_to_wav);
        btnPlayStream = findViewById(R.id.play_stream);
        btnStopStream = findViewById(R.id.stop_stream);

        pcmFile = new File(getExternalFilesDir(null), "demo.pcm");
        Log.d(TAG, "pcm file path " + pcmFile);

        btnStartRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                audioRecordBufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRateInHz, channelConfig, audioFormat, audioRecordBufferSize);
                audioRecord.startRecording();
                isRecording = true;
                tvState.setText("录制中...");

                new Thread(new RecordRunnable()).start();
            }
        });

        btnStopRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isRecording = false;
                audioRecord.stop();
                audioRecord.release();
                tvState.setText("录制完成");
            }
        });

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!pcmFile.exists()) {
                    Toast.makeText(MainActivity.this, "PCM 文件不存在", Toast.LENGTH_LONG).show();
                    return;
                }


                playStatic();
            }
        });

        btnConvert2Wav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!pcmFile.exists()) {
                    Toast.makeText(MainActivity.this, "PCM 文件不存在", Toast.LENGTH_LONG).show();
                    return;
                }

                String wavFile = getExternalFilesDir(null) + File.separator + "demo.wav";
                new PcmToWavUtil(sampleRateInHz, channelConfig, audioFormat).pcmToWav(pcmFile.getAbsolutePath(), wavFile);
                Toast.makeText(MainActivity.this, "转换成功", Toast.LENGTH_SHORT).show();
            }
        });

        btnPlayStream.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!pcmFile.exists()) {
                    Toast.makeText(MainActivity.this, "PCM 文件不存在", Toast.LENGTH_LONG).show();
                    return;
                }

                playStream();
            }
        });

        btnStopStream.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isPlaying = false;
                releaseAudioTrack();
            }
        });
    }

    class RecordRunnable implements Runnable {
        @Override
        public void run() {
            byte[] data = new byte[audioRecordBufferSize];
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(pcmFile);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            if (out == null) {
                return;
            }

            try {
                while (isRecording) {
                    int len = audioRecord.read(data, 0, audioRecordBufferSize);
                    out.write(data, 0, len);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // 播放录制的 PCM 文件 ( MODE_STATIC 方式)
    private void playStatic() {
        byte[] data = file2Bytes();
        Log.d(TAG, "data len " + data.length);

        AudioAttributes attributes = new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        AudioFormat audioFormat = new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        audioTrack = new AudioTrack(attributes, audioFormat, data.length, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
        int ret = audioTrack.write(data, 0, data.length);
        if (ret > 0) {
            audioTrack.play();
        } else {
            Log.d(TAG, "error code is " + ret);
        }
    }

    // 循环播放录制的 PCM 文件 ( MODE_STREAM 方式)
    private void playStream() {
        AudioAttributes attributes = new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        AudioFormat audioFormat = new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        int bufferSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        Log.d(TAG, "buffer size is " + bufferSize);
        audioTrack = new AudioTrack(attributes, audioFormat, bufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        // 先调用 Play 方法， 然后开启线程不断的将 PCM 流写入 AudioTrack 的 buffer
        audioTrack.play();

        isPlaying = true;
        new Thread(new PlayStreamRunnable(bufferSize)).start();
    }

    class PlayStreamRunnable implements Runnable {
        int bufferSize;

        PlayStreamRunnable(int bufferSize) {
            this.bufferSize = bufferSize;
        }

        @Override
        public void run() {
            byte[] data = file2Bytes();
            int len = data.length;
            int currentPosition = 0;
            while (isPlaying) {
                if (len > bufferSize) {
                    // 循环播放
                    audioTrack.write(data, currentPosition, bufferSize);
                    currentPosition += bufferSize;
                    if (currentPosition + bufferSize >= len) {
                        audioTrack.write(data, currentPosition, len - currentPosition);
                        currentPosition = 0;
                    }
                } else {
                    audioTrack.write(data, 0, len);
                }
            }
        }
    }

    private byte[] file2Bytes() {
        FileInputStream in = null;
        ByteArrayOutputStream out = null;
        byte[] data = null;
        try {
            in = new FileInputStream(pcmFile);
            out = new ByteArrayOutputStream();
            int len;
            byte[] buffer = new byte[1024];
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }

            data = out.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return data;
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseAudioTrack();
    }

    private void releaseAudioTrack() {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }
    }

    private void requestPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
    }
}

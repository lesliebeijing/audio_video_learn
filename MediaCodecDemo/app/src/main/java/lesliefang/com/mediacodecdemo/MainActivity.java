package lesliefang.com.mediacodecdemo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

// Android5.0 以上以异步方式获取 codec buffer
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MediaCodec";
    private static final int QUEUE_SIZE = 10;
    Button btnStartRecord;
    Button btnStopRecord;

    // API 16 (4.1) 以后
    MediaCodec mediaCodec;

    AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private volatile boolean isCodecComplete = false;

    ArrayBlockingQueue<byte[]> inputQueue = new ArrayBlockingQueue<>(QUEUE_SIZE); // codec 输入队列
    ArrayBlockingQueue<byte[]> outputQueue = new ArrayBlockingQueue<>(QUEUE_SIZE); // codec 输出队列

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartRecord = findViewById(R.id.btn_start_record);
        btnStopRecord = findViewById(R.id.btn_stop_record);

        // 这里为了简单不处理权限回调，请开启所有权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 100);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
        }

        btnStartRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "开始了", Toast.LENGTH_SHORT).show();
                int bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                audioRecord.startRecording();
                isRecording = true;

                startCodec();
                new Thread(new RecordRunnable(bufferSize)).start();
                new Thread(new WriteRunnable()).start();
            }
        });

        btnStopRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isRecording = false;
                Toast.makeText(MainActivity.this, "结束了", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startCodec() {
        MediaFormat mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        final MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        String name = mediaCodecList.findEncoderForFormat(mediaFormat);
        Log.d(TAG, "name is " + name); // OMX.google.aac.encoder
        try {
            mediaCodec = MediaCodec.createByCodecName(name);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 默认在调用它的线程中运行，这里是主线程。也可以传一个 handle 进去让它运行在 handler 所在的线程
        // 异步模式需要 API Level 21（5.0） 以上
        // API Level 23 (6.0) 以上才支持传 handler 参数
        mediaCodec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                Log.d(TAG, "onInputBufferAvailable");
                byte[] data = null;
                if (isRecording) {
                    try {
                        data = inputQueue.take();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    data = inputQueue.poll();
                    Log.d(TAG, "11111111111111111 " + inputQueue.size());
                }

                if (data != null) {
                    // 只要有数据就放入 codec (无论录制是否结束)
                    ByteBuffer inputBuffer = codec.getInputBuffer(index);
                    inputBuffer.clear();
                    inputBuffer.put(data);
                    codec.queueInputBuffer(index, 0, data.length, System.currentTimeMillis() * 1000, 0);
                    Log.d(TAG, "codec 入队 " + Thread.currentThread().getName());
                } else if (!isRecording) {
                    // 录制结束且队列中没有数据说明已经没有输入了，用一个带 BUFFER_FLAG_END_OF_STREAM 的空 buffer 标志输入结束
                    mediaCodec.queueInputBuffer(index, 0, 0, System.currentTimeMillis() * 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    Log.d(TAG, "codec 最后一个空 buffer 了");
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                Log.d(TAG, "codec 出队 " + Thread.currentThread().getName());
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                byte[] outData = new byte[info.size];
                outputBuffer.get(outData);
                codec.releaseOutputBuffer(index, false);

                try {
                    outputQueue.put(outData);
                    Log.d(TAG, "outputQueue 入队");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "flags " + info.flags);
                    Log.d(TAG, "codec 处理完毕了");
                    isCodecComplete = true;

                    mediaCodec.stop();
                    mediaCodec.release();
                }
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.d(TAG, "codec onError " + e.getMessage());
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                // 输出格式改变时被调用
            }
        });
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaCodec.start();
        isCodecComplete = false;
    }

    class RecordRunnable implements Runnable {
        int bufferSize;

        RecordRunnable(int bufferSize) {
            this.bufferSize = bufferSize;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[bufferSize];

            while (isRecording) {
                int len = audioRecord.read(buffer, 0, bufferSize);
                if (len <= 0) {
                    continue;
                }

                byte[] data = new byte[len];
                System.arraycopy(buffer, 0, data, 0, len);

                try {
                    inputQueue.put(data); // 阻塞方法
                    Log.d(TAG, "inputQueue 入队了");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            audioRecord.stop();
            audioRecord.release();
            Log.d(TAG, "录制线程结束");
        }
    }

    class WriteRunnable implements Runnable {

        @Override
        public void run() {
            File file = new File(getExternalFilesDir(null), "demo.aac");
            FileOutputStream out = null;

            try {
                out = new FileOutputStream(file);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            if (out == null) {
                return;
            }

            try {
                byte[] header = new byte[7];
                while (true) {
                    byte[] data;
                    if (!isCodecComplete) {
                        data = outputQueue.take();
                    } else {
                        data = outputQueue.poll();
                    }

                    if (data != null) {
                        // 队列非空就处理（不论 codec 是否处理完成）
                        addADTStoPacket(header, data.length + 7);
                        out.write(header);
                        out.write(data);
                        Log.d(TAG, "aac 写入文件");
                    } else if (isCodecComplete) {
                        // 队列空且 codec 结束说明所有数据都写完了
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            Log.d(TAG, "aac写线程结束");
        }
    }

    /**
     * 添加ADTS头部
     *
     * @param packet    ADTS header 的 byte[]，长度为7
     * @param packetLen 该帧的长度，包括header的长度
     */
    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = 2; // AAC LC
        int freqIdx = 3; // 48000Hz
        int chanCfg = 2; // 2 Channel

        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }
}

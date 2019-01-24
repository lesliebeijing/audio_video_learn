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

// 5.0以后以同步方式获取 codec buffer。(如果需要兼容4.X 请相应修改获取 buffer 的方式)
public class Main2Activity extends AppCompatActivity {
    private static final String TAG = "MediaCodec";
    private static final int QUEUE_SIZE = 10;
    Button btnStartRecord;
    Button btnStopRecord;

    // API 16 (4.1) 以后
    MediaCodec mediaCodec;

    AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private volatile boolean isCodecComplete = false;

    ArrayBlockingQueue<byte[]> inputQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    ArrayBlockingQueue<byte[]> outputQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);

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
                Toast.makeText(Main2Activity.this, "开始了", Toast.LENGTH_SHORT).show();
                int bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                audioRecord.startRecording();
                isRecording = true;

                startCodec();
                new Thread(new RecordRunnable(bufferSize)).start();
                new Thread(new CodecRunnable()).start();
                new Thread(new WriteRunnable()).start();
            }
        });

        btnStopRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isRecording = false;
                Toast.makeText(Main2Activity.this, "结束了", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startCodec() {
        MediaFormat mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        String name = mediaCodecList.findEncoderForFormat(mediaFormat);
        Log.d(TAG, "name is " + name); // OMX.google.aac.encoder
        try {
            mediaCodec = MediaCodec.createByCodecName(name);
        } catch (IOException e) {
            e.printStackTrace();
        }

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

                if (inputQueue.size() == QUEUE_SIZE) {
                    try {
                        Thread.sleep(100);
                        Log.d(TAG, "inputQueue 满了等待。。。");
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    inputQueue.offer(data);
                    Log.d(TAG, "inputQueue 入队 " + data.length);
                }
            }

            audioRecord.stop();
            audioRecord.release();
            Log.d(TAG, "录制线程结束");
        }
    }

    class CodecRunnable implements Runnable {

        @Override
        public void run() {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            for (; ; ) {
                Log.d(TAG, "codec thread running...");
                byte[] data = inputQueue.poll();
                if (data != null) {
                    // 只要有数据就放入 codec (无论录制是否结束)
                    // dequeueInputBuffer 后要及时提交（queueInputBuffer）到 codec，否则 input buffer 用尽后 dequeueInputBuffer 会一直返回 -1
                    int inputBufferId = mediaCodec.dequeueInputBuffer(1000);
                    Log.d(TAG, "inputBufferId " + inputBufferId);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferId);
                        inputBuffer.clear();
                        inputBuffer.put(data);
                        mediaCodec.queueInputBuffer(inputBufferId, 0, data.length, System.currentTimeMillis() * 1000, 0);
                        Log.d(TAG, "codec 入队 ");
                    } else {
                        Log.d(TAG, "codec 入队失败数据丢失，没有可用 buffer");
                    }
                } else if (!isRecording) {
                    // 录制结束且队列中没有数据说明已经没有输入了，用一个带 BUFFER_FLAG_END_OF_STREAM 的空 buffer 标志输入结束
                    int inputBufferId = mediaCodec.dequeueInputBuffer(1000);
                    Log.d(TAG, "inputBufferId2 " + inputBufferId);
                    if (inputBufferId >= 0) {
                        mediaCodec.queueInputBuffer(inputBufferId, 0, 0, System.currentTimeMillis() * 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        Log.d(TAG, "codec 最后一个空 buffer 了");
                    } else {
                        Log.d(TAG, "最后一个空 buffer 入队失败，没有可用 buffer");
                    }
                } else {
                    // 录制未结束，队列为空，这里什么也不做
                    // 这里不要 sleep ,因为没有输入时还要处理输出呢
                    Log.d(TAG, "inputQueue 为空。。。");
                }

                // dequeueOutputBuffer 用完后要及时 releaseOutputBuffer ，否则 out buffer 会用尽后 dequeueOutputBuffer 会一直返回 -1
                int outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, 1000);
                Log.d(TAG, "outputBufferId " + outputBufferId);
                if (outputBufferId >= 0) {
                    ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferId);
                    byte[] outData = new byte[bufferInfo.size];
                    outputBuffer.get(outData);
                    mediaCodec.releaseOutputBuffer(outputBufferId, false);
                    Log.d(TAG, "codec 出队 size " + bufferInfo.size + " flag " + bufferInfo.flags);

                    if (outputQueue.size() == QUEUE_SIZE) {
                        try {
                            // outputQueue 满后要等一下，否则解码后数据会丢失
                            // 睡一小会对 codec 线程影响不大
                            Thread.sleep(100);
                            Log.d(TAG, "outputQueue 满了等待。。。");
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else {
                        outputQueue.offer(outData);
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "flags " + bufferInfo.flags);
                        Log.d(TAG, "codec 处理完毕了");
                        isCodecComplete = true;
                        break;
                    }
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Subsequent data will conform to new format.
                    // Can ignore if using getOutputFormat(outputBufferId)
                }
            }

            mediaCodec.stop();
            mediaCodec.release();
            Log.d(TAG, "codec 线程结束");
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
                    byte[] data = outputQueue.poll();
                    Log.d(TAG, "write thread running");
                    if (data != null) {
                        // 队列非空就处理（不论 codec 是否处理完成）
                        addADTStoPacket(header, data.length + 7);
                        out.write(header);
                        out.write(data);
                        Log.d(TAG, "aac 写入文件");
                    } else if (isCodecComplete) {
                        // 队列空且 codec 结束说明所有数据都写完了
                        break;
                    } else {
                        // 队列空且 codec 未结束，这时要等待一下
                        try {
                            Thread.sleep(200);
                            Log.d(TAG, "暂无数据 aac 写线程等待。。。");
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
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

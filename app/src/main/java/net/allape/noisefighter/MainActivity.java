package net.allape.noisefighter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 2021;

    // 日期格式化
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.CHINA);

    // WAV文件头长度
    private static final int WAV_HEADER_SIZE = 44;

    // OneShot震动
    private static final VibrationEffect ONE_SHOT = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE);

    // 采样率
    private static final int SAMPLE_RATE_IN_HZ = 44100;
//    private static final int SAMPLE_RATE_IN_HZ = 30000;
    // 频道, 单声道为1
    private static final int CHANNEL = 1;
    // 码率 bytes per second
    private static final int BYTE_RATE = SAMPLE_RATE_IN_HZ * CHANNEL * 2;
    // 音频采样大小 bit
    private static final int SAMPLE_LENGTH = 16;
    // 音频采样格式
    private static final int SAMPLE_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // 触发之后需要延后录音的数量, 避免闪烁
    private static final int RECORDED_TRAILING_SIZE = 10;
    // 最大缓存数量, 超出时直接播放
    private static final int RECORDED_MAX_SIZE = 2000;
    // 缓存录音
    private static final ArrayList<byte[]> recorded = new ArrayList<>(RECORDED_MAX_SIZE);

    // 是否初始化
    private boolean initialized = false;

    // 需要进行记录的阈值: 峰值达到这个阈值开始录音、离开这个阈值结束录音(如果已经开始录音了)
    // private int threshold = 10000;
    private int threshold = 33000;
    // 是否正在播放声音
    private boolean playing = false;
    // 防闪烁计数
    private int trailingCount = RECORDED_TRAILING_SIZE;

    // 震动器
    Vibrator vibrator;
    // 录音器
    private MicRunnable micRunnable;
    // 播放器
    private AudioTrack track;

    // 文件写入流, 不为null则表示需要写入文件
    private RandomAccessFile wav;
    // 已经写入了的数据长度(byte数量)
    private long wavBytes = 0;

    // 是否暂停图表刷新
    private boolean chartPaused = false;
    // 图表压缩内容的值
    private final int chartScale = 1 << 5;

    private LineChart chart;

    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 检查权限
        boolean hasPermissions = EasyPermissions.hasPermissions(
                this,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE
        );
        if (hasPermissions) {
            this.init();
        } else {
            // shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
            }, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (micRunnable != null) micRunnable.close();
        if (wav != null) {
            try {
                wav.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    Toast.makeText(this, "Please grant all permissions in app settings", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
        this.init();
    }

    /**
     * 初始化组件
     */
    private void init() {
        if (initialized) {
            Log.w(LOG_TAG, "has been initialized");
            return;
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // 录音文件
        final TextView recordFilePath = findViewById(R.id.record_file_path);
        recordFilePath.setText(R.string.RecordFilePathDefault);

        // 录音按钮
        final Button recordButton = findViewById(R.id.record_button);
        recordButton.setOnClickListener(view -> {
            vibrator.vibrate(ONE_SHOT);
            // 初始化写入功能
            if (wav == null) {
                try {
                    String fileName = getExternalFilesDir(null) + "/" + FORMAT.format(new Date()) + ".wav";
                    File wavFile = new File(fileName);
                    if (!wavFile.exists()) {
                        if (!wavFile.createNewFile()) {
                            throw new IOException("failed to create wav file: " + fileName);
                        }
                    }
                    recordFilePath.setText(fileName);
                    wav = new RandomAccessFile(wavFile, "rw");
                    wav.seek(WAV_HEADER_SIZE);
                    Log.v(LOG_TAG, "start recording to file: " + fileName);
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(LOG_TAG, e.getMessage());
                    Toast.makeText(this, "Failed to create WAV file", Toast.LENGTH_LONG).show();
                }
                recordButton.setText(R.string.StopRecording);
            } else {
                try {
                    // 关闭并保存头部数据 https://zh.wikipedia.org/wiki/WAV
                    byte[] header = new byte[WAV_HEADER_SIZE];
                    // RIFF/WAVE header
                    header[0] = 'R';
                    header[1] = 'I';
                    header[2] = 'F';
                    header[3] = 'F';
                    int totalBytesLength = (int) (wavBytes + 36);
                    header[4] = (byte) (totalBytesLength & 0xff);
                    header[5] = (byte) ((totalBytesLength >> 8) & 0xff);
                    header[6] = (byte) ((totalBytesLength >> 16) & 0xff);
                    header[7] = (byte) ((totalBytesLength >> 24) & 0xff);
                    //WAVE
                    header[8] = 'W';
                    header[9] = 'A';
                    header[10] = 'V';
                    header[11] = 'E';
                    // 'fmt ' chunk
                    header[12] = 'f';
                    header[13] = 'm';
                    header[14] = 't';
                    header[15] = ' ';
                    // 4 bytes: size of 'fmt ' chunk
                    header[16] = 16;
                    header[17] = 0;
                    header[18] = 0;
                    header[19] = 0;
                    // format = 1
                    header[20] = 1;
                    header[21] = 0;
                    header[22] = (byte) CHANNEL;
                    header[23] = 0;
                    header[24] = (byte) (SAMPLE_RATE_IN_HZ & 0xff);
                    header[25] = (byte) ((SAMPLE_RATE_IN_HZ >> 8) & 0xff);
                    header[26] = (byte) ((SAMPLE_RATE_IN_HZ >> 16) & 0xff);
                    header[27] = (byte) ((SAMPLE_RATE_IN_HZ >> 24) & 0xff);
                    header[28] = (byte) (BYTE_RATE & 0xff);
                    header[29] = (byte) ((BYTE_RATE >> 8) & 0xff);
                    header[30] = (byte) ((BYTE_RATE >> 16) & 0xff);
                    header[31] = (byte) ((BYTE_RATE >> 24) & 0xff);
                    // block align
                    header[32] = (byte) (CHANNEL * SAMPLE_LENGTH / 8);
                    header[33] = 0;
                    // bits per sample
                    header[34] = SAMPLE_LENGTH;
                    header[35] = 0;
                    //data
                    header[36] = 'd';
                    header[37] = 'a';
                    header[38] = 't';
                    header[39] = 'a';
                    header[40] = (byte) (wavBytes & 0xff);
                    header[41] = (byte) ((wavBytes >> 8) & 0xff);
                    header[42] = (byte) ((wavBytes >> 16) & 0xff);
                    header[43] = (byte) ((wavBytes >> 24) & 0xff);
                    wav.seek(0);
                    wav.write(header);

                    Log.v(LOG_TAG, "wav wrote header: " + WAV_HEADER_SIZE + "bytes");

                    wav.close();
                    wav = null;
                    wavBytes = 0;
                } catch (IOException e) {
                    e.printStackTrace();
                }
                recordButton.setText(R.string.StartRecording);
            }
        });

        // 图表暂停/开始刷新按钮
        Button chartButton = findViewById(R.id.chart_button);
        chartButton.setOnClickListener(view -> {
            vibrator.vibrate(ONE_SHOT);
            chartPaused = !chartPaused;
        });

        // 图表
        chart = findViewById(R.id.audio_chart);
        chart.setBackgroundColor(Color.WHITE);
        chart.setDrawGridBackground(true);
        chart.setDrawBorders(true);
        chart.getDescription().setEnabled(false);
        chart.setPinchZoom(false);

        Legend l = chart.getLegend();
        l.setEnabled(false);

        XAxis xAxis = chart.getXAxis();
        xAxis.setEnabled(false);

        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setAxisMaximum(Short.MAX_VALUE);
        leftAxis.setAxisMinimum(Short.MIN_VALUE);
        leftAxis.setDrawAxisLine(false);
        leftAxis.setDrawZeroLine(false);
        leftAxis.setDrawGridLines(false);

        chart.getAxisRight().setEnabled(false);

        chart.invalidate();

        handler = new Handler();

        // 滑条
        Slider thresholdSlider = findViewById(R.id.threshold_slider);
        thresholdSlider.setValue(threshold);
        thresholdSlider.addOnChangeListener((slider, value, fromUser) -> {
            threshold = (int) value;
            vibrator.vibrate(ONE_SHOT);
        });

        // 录音器
        micRunnable = new MicRunnable(SAMPLE_RATE_IN_HZ, data -> {
            // 图表用的数据
            ArrayList<Entry> values = new ArrayList<>(data.length / chartScale);

            // 振幅峰值
            short peak = 0;
            for (int i = 0; i < data.length; i+=2) {
                short one = (short) (data[i] | (data[i + 1] << 8));

                // 避免显示过多
                if (i % chartScale == 0) {
                    values.add(new Entry(i, one));
                }

                if (one > peak) {
                    peak = one;
                }
            }

            // 正在播放声音时不处理任何数据
            if (!playing) {
                boolean triggerThreshold = peak >= threshold;
                if (triggerThreshold || trailingCount < RECORDED_TRAILING_SIZE) {
                    if (triggerThreshold) trailingCount = 0;
                    // 图表背景颜色改为绿色
                    chart.setBackgroundColor(Color.RED);
                    if (recorded.size() >= RECORDED_MAX_SIZE) {
                        play();
                    } else {
                        // 噪音问题来源与
                        byte[] newData = new byte[data.length];
                        System.arraycopy(data, 0, newData, 0, newData.length);
                        recorded.add(newData);
                        trailingCount++;
                    }
                } else {
                    chart.setBackgroundColor(Color.WHITE);
                    // 如果存在录音数据则暂停录音、开始播放
                    if (recorded.size() > 0) {
                        play();
                    }
                }
            }

            // 重新渲染图表
            if (!chartPaused) {
                handler.post(() -> setData(values));
                // setData(values);
            }

            // 写入数据
            if (wav != null) {
                try {
                    wav.write(data);
                    wavBytes += data.length;
                    Log.w(LOG_TAG, "wav wrote: " + data.length + "bytes, total: " + wavBytes + "bytes");
                    if (wavBytes >= Integer.MAX_VALUE) {
                        Log.w(LOG_TAG, "wav file overflow");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(LOG_TAG, e.getMessage());
                }
            }
        });
        new Thread(micRunnable).start();

        // 播放器
        // noinspection deprecation
        track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE_IN_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                SAMPLE_FORMAT,
                micRunnable.getBufferSize(),
                AudioTrack.MODE_STREAM
        );
//        track = new AudioTrack.Builder()
//                .setAudioAttributes(new AudioAttributes.Builder()
//                        .setUsage(AudioAttributes.USAGE_MEDIA)
//                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
//                        .build())
//                .setAudioFormat(new AudioFormat.Builder()
//                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
//                        .setEncoding(SAMPLE_FORMAT)
//                        .setSampleRate(SAMPLE_RATE_IN_HZ)
//                        .build())
//                .setBufferSizeInBytes(micRunnable.getBufferSize())
//                .build();

        initialized = true;
    }

    /**
     * 绘制图表
     * @param values 绘制的数据
     */
    private void setData(ArrayList<Entry> values) {

        //ArrayList<Entry> values = new ArrayList<>();
        //for (int i = 0; i < count; i++) {
        //    float val = (float) (Math.random() * range) + 50;
        //    values.add(new Entry(i, val));
        //}

        LineDataSet lineDataSet;

        if (chart.getData() != null &&
                chart.getData().getDataSetCount() > 0) {
            lineDataSet = (LineDataSet) chart.getData().getDataSetByIndex(0);
            lineDataSet.setValues(values);
            chart.getData().notifyDataChanged();
            chart.notifyDataSetChanged();
            chart.postInvalidate();
        } else {
            lineDataSet = new LineDataSet(values, "Audio");

            lineDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
            lineDataSet.setColor(Color.BLACK);
            lineDataSet.setLineWidth(2f);
            lineDataSet.setDrawCircles(false);
            lineDataSet.setFillAlpha(255);
            lineDataSet.setDrawFilled(false);
            lineDataSet.setFillColor(Color.WHITE);
            lineDataSet.setHighLightColor(Color.RED);
            lineDataSet.setDrawCircleHole(false);

            ArrayList<ILineDataSet> dataSets = new ArrayList<>();
            dataSets.add(lineDataSet); // add the data sets

            LineData data = new LineData(dataSets);
            data.setDrawValues(false);

            // set data
            chart.setData(data);
        }
    }

    /**
     * 播放{@link this#recorded}录音
     */
    synchronized private void play() {
        if (recorded.size() == 0) {
            return;
        }

        try {
            // 设置为绿色表示在播放录音
            chart.setBackgroundColor(Color.GREEN);
        } catch (Exception e) {
            e.printStackTrace();
        }

        playing = true;

        try {
            track.play();
            int shortLength = recorded.get(0).length;
//            byte[] data = new byte[recorded.size() * shortLength];
//            for (int i = 0; i < recorded.size(); i++) {
//                System.arraycopy(recorded.get(i), 0, data, i * shortLength, shortLength);
//            }
//            Log.v(LOG_TAG, "Playback with: " + data.length + " * 2 bytes");
//            track.write(data, 0, data.length);

            for (byte[] bytes : recorded) {
                track.write(bytes, 0, bytes.length);
            }
            Log.v(LOG_TAG, "Playback with: " + recorded.size() * shortLength + "bytes");

            track.stop();
            track.flush();

            Thread.sleep(1000);
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(LOG_TAG, e.getMessage());
        } finally {
            playing = false;
            // 清空缓存
            recorded.clear();
        }
    }

    /**
     * 录音器
     */
    static class MicRunnable implements Runnable {

        // 采样率
        protected final int rateInHz;
        // 录音回调
        protected final AudioRecordRunnableCallback callback;

        // 是否在下个循环停止录音
        protected boolean endAtNext = false;
        // buffer大小
        protected int bufferSize;

        public MicRunnable(int rateInHz, AudioRecordRunnableCallback callback) {
            this.rateInHz = rateInHz;
            this.callback = callback;

            bufferSize = AudioRecord.getMinBufferSize(rateInHz,
                    AudioFormat.CHANNEL_IN_MONO, SAMPLE_FORMAT) * 2;
        }

        @Override
        public void run() {
            // Set the thread priority
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, rateInHz,
                    AudioFormat.CHANNEL_IN_MONO, SAMPLE_FORMAT, bufferSize);

            int bufferReadResult;
            byte[] audioData = new byte[bufferSize];

            Log.d(LOG_TAG, "audioRecord.startRecord()");
            audioRecord.startRecording();

            while (!endAtNext) {
                bufferReadResult = audioRecord.read(audioData, 0, audioData.length);
                if (bufferReadResult > 0) {
                    try {
                        callback.onData(audioData);
                    } catch (Exception e) {
                        Log.e(LOG_TAG, e.getMessage());
                        e.printStackTrace();
                    }
                }
            }

            Log.v(LOG_TAG,"AudioThread Finished");

            audioRecord.stop();
            audioRecord.release();

            Log.v(LOG_TAG,"audio record released");
        }

        public void close() {
            this.endAtNext = true;
        }

        public int getBufferSize() {
            return bufferSize;
        }

        interface AudioRecordRunnableCallback {
            void onData(byte[] data);
        }

    }

    /**
     * 正弦发生器 - 有问题
     */
    @SuppressWarnings("unused")
    static class ToneRunnable extends MicRunnable {

        private final double increment;

        public ToneRunnable(int rateInHz, AudioRecordRunnableCallback callback) {
            this(rateInHz, 1000, callback);
        }

        public ToneRunnable(int rateInHz, int frequency, AudioRecordRunnableCallback callback) {
            super(rateInHz, callback);
            increment = 2 * Math.PI * frequency / rateInHz;
            bufferSize = (int) (180 / increment);
            bufferSize *= 2;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[bufferSize * 2];
            double[] samples = new double[bufferSize];
            double angle = 0;

            // 处理一个buffer所需要的时间
            long oneBufferTime = 1000 / (rateInHz / bufferSize);

            while (!endAtNext) {
                long beforeProcess = System.currentTimeMillis();
                for (int i = 0, j = 0; i < samples.length; i++, j+=2) {
                    samples[i] = Math.sin(angle);
                    short oneShort = (short) (samples[i] * Short.MAX_VALUE);
                    buffer[j] = (byte) oneShort;
                    buffer[j + 1] = (byte) (oneShort >>> 8);
                    angle = angle + increment;
                }
                try {
                    long waitingTime = oneBufferTime - (System.currentTimeMillis() - beforeProcess);
                    if (waitingTime > 0) {
                        //noinspection BusyWait
                        Thread.sleep(waitingTime);
                    }
                    callback.onData(buffer);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

    }

}
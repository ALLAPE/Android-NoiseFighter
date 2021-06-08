package net.allape.noisefighter;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Button;
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

import java.util.ArrayList;

import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity {

    private static final String LOG_TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 2021;

    // OneShot震动
    private static final VibrationEffect ONE_SHOT = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE);

    // 采样率
    private static final int SAMPLE_RATE_IN_HZ = 44100;
    // 频道, 单声道为1
    private static final int CHANNEL = 1;
    // 码率 bytes per second
    private static final int BYTE_RATE = SAMPLE_RATE_IN_HZ * CHANNEL * 2;

    // 最大缓存数量, 超出时直接播放
    private static final int RECORDED_MAX_SIZE = 10000;
    // 缓存录音
    private static final ArrayList<byte[]> recorded = new ArrayList<>(RECORDED_MAX_SIZE);

    // 是否初始化
    private boolean initialized = false;

    // 需要进行记录的阈值: 峰值达到这个阈值开始录音、离开这个阈值结束录音(如果已经开始录音了)
    // private int threshold = 10000;
    private int threshold = 33000;
    // 是否正在播放声音
    private boolean playing = false;

    // 震动器
    Vibrator vibrator;
    // 录音器
    private MicRunnable micRunnable;
    // 播放器
    private AudioTrack track;

    private Button recordButton;
    private LineChart chart;

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
        if (track != null) track.release();
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

        // 录音按钮
        recordButton = findViewById(R.id.record_button);
        recordButton.setOnClickListener(view -> {

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
            ArrayList<Entry> values = new ArrayList<>(data.length);

            // 峰值音量
            short peak = 0;
            for (int i = 0; i < data.length; i+=2) {
                short one = (short) (data[i] | (data[i + 1] << 8));

                // 避免显示过多
                if (i % (1 << 4) == 0) {
                    values.add(new Entry(i, one));
                }

                if (one  > peak) {
                    peak = one;
                }
            }

            // 正在播放声音时不处理任何数据
            if (!playing) {
                if (peak >= threshold) {
                    // 图表背景颜色改为绿色
                    chart.setBackgroundColor(Color.RED);
                    if (recorded.size() + 1 == RECORDED_MAX_SIZE) {
                        play();
                    } else {
                        recorded.add(data);
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
            setData(values);
        });
        new Thread(micRunnable).start();

        // 播放器
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_IN_HZ)
                        .build())
                .setBufferSizeInBytes(micRunnable.getBufferSize())
                .build();

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
            byte[] data = new byte[recorded.size() * shortLength];
            for (int i = 0; i < recorded.size(); i++) {
                System.arraycopy(recorded.get(i), 0, data, i * shortLength, shortLength);
            }
            Log.v(LOG_TAG, "Playback with: " + data.length + " * 2 bytes");
            track.write(data, 0, data.length);
            // 延迟操作
            Thread.sleep(1000);
            track.stop();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            playing = false;
            track.flush();
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
        private final int bufferSize;

        public MicRunnable(int rateInHz, AudioRecordRunnableCallback callback) {
            this.rateInHz = rateInHz;
            this.callback = callback;

            bufferSize = AudioRecord.getMinBufferSize(rateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        }

        @Override
        public void run() {
            // Set the thread priority
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, rateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

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
     * 正弦发生器
     */
    static class ToneRunnable extends MicRunnable {

        private static final int BUFFER_SIZE = 2048;

        private final int frequency;

        public ToneRunnable(int rateInHz, AudioRecordRunnableCallback callback) {
            this(rateInHz, 1000, callback);
        }

        public ToneRunnable(int rateInHz, int frequency, AudioRecordRunnableCallback callback) {
            super(rateInHz, callback);
            this.frequency = frequency;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[BUFFER_SIZE];
            double increment = 2 * Math.PI * frequency / rateInHz;
            double angle = 0;
            double[] samples = new double[BUFFER_SIZE / 2];

            // 处理一个buffer所需要的时间
            long oneBufferTime = 1000 / (rateInHz / BUFFER_SIZE);

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

        @Override
        public int getBufferSize() {
            return BUFFER_SIZE;
        }

    }

}
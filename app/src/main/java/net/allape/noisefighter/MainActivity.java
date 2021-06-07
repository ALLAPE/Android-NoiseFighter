package net.allape.noisefighter;

import android.Manifest;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

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

    // 采样率
    private static final int SAMPLE_RATE_IN_HZ = 44100;

    // 最大缓存数量, 超出时直接播放
    private static final int RECORDED_MAX_SIZE = 10000;
    // 缓存录音
    private static final ArrayList<short[]> recorded = new ArrayList<>(RECORDED_MAX_SIZE);


    // 需要进行记录的阈值: 峰值达到这个阈值开始录音、离开这个阈值结束录音(如果已经开始录音了)
    // = Short.MAX_VALUE / 3
    private int threshold = 10000;
    // 是否正在播放声音
    private boolean playing = false;

    private LineChart chart;
    private AudioRecordRunnable audioRecordRunnable;
    private AudioTrack track;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 检查权限
        boolean hasPermissions = EasyPermissions.hasPermissions(this, Manifest.permission.RECORD_AUDIO);
        if (!hasPermissions) {
            Toast.makeText(this, "Please grant all permissions in app settings", Toast.LENGTH_LONG).show();
            return;
        }

        Slider thresholdSlider = findViewById(R.id.threshold_slider);
        thresholdSlider.setValue(threshold);
        thresholdSlider.addOnChangeListener((slider, value, fromUser) -> threshold = (int) value);

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
        leftAxis.setAxisMinimum(0);
        leftAxis.setDrawAxisLine(false);
        leftAxis.setDrawZeroLine(false);
        leftAxis.setDrawGridLines(false);

        chart.getAxisRight().setEnabled(false);

        chart.invalidate();

        // 录音器
        audioRecordRunnable = new AudioRecordRunnable(SAMPLE_RATE_IN_HZ, data -> {

            // 图表用的数据
            ArrayList<Entry> values = new ArrayList<>(data.length);

            // 峰值音量
            short peak = 0;
            for (int i = 0; i < data.length; i++) {
                short one = data[i];
                values.add(new Entry(i, one));

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
        new Thread(audioRecordRunnable).start();

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
                .setBufferSizeInBytes(audioRecordRunnable.getBufferSize())
                .build();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.audioRecordRunnable.close();
//        this.chart.clear();
    }

    /**
     * 绘制图表
     * @param values 绘制的数据
     */
    private void setData(ArrayList<Entry> values) {

//        ArrayList<Entry> values = new ArrayList<>();
//
//        for (int i = 0; i < count; i++) {
//            float val = (float) (Math.random() * range) + 50;
//            values.add(new Entry(i, val));
//        }

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
            lineDataSet.setDrawCircles(false);
            lineDataSet.setLineWidth(2f);
            lineDataSet.setCircleRadius(3f);
            lineDataSet.setFillAlpha(255);
            lineDataSet.setDrawFilled(true);
            lineDataSet.setFillColor(Color.WHITE);
            lineDataSet.setHighLightColor(Color.RED);
            lineDataSet.setDrawCircleHole(false);
            lineDataSet.setFillFormatter((dataSet, dataProvider) -> {
                // return 0;
                return chart.getAxisLeft().getAxisMinimum();
            });

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
            for (short[] data : recorded) {
                track.write(data, 0, data.length);
            }
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
    static class AudioRecordRunnable implements Runnable {

        // 采样率
        private final int rateInHz;
        // 录音回调
        private final AudioRecordRunnableCallback callback;

        // 是否在下个循环停止录音
        private boolean endAtNext = false;
        // buffer大小
        private final int bufferSize;

        public AudioRecordRunnable(int rateInHz, AudioRecordRunnableCallback callback) {
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
            short[] audioData;
            int bufferReadResult;
            AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, rateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            audioData = new short[bufferSize];

            Log.d(LOG_TAG, "audioRecord.startRecord()");
            audioRecord.startRecording();

            while (!endAtNext) {
                bufferReadResult = audioRecord.read(audioData, 0, audioData.length);
                if (bufferReadResult > 0) {
                    try {
                        callback.onShortArray(audioData);
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
            void onShortArray(short[] data);
        }

    }

}
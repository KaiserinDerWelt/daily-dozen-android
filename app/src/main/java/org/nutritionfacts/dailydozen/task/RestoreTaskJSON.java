package org.nutritionfacts.dailydozen.task;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.collection.ArrayMap;

import com.activeandroid.ActiveAndroid;
import com.google.gson.Gson;

import org.nutritionfacts.dailydozen.Common;
import org.nutritionfacts.dailydozen.R;
import org.nutritionfacts.dailydozen.controller.Bus;
import org.nutritionfacts.dailydozen.exception.InvalidDateException;
import org.nutritionfacts.dailydozen.model.DDServings;
import org.nutritionfacts.dailydozen.model.Day;
import org.nutritionfacts.dailydozen.model.DayEntries;
import org.nutritionfacts.dailydozen.model.Food;
import org.nutritionfacts.dailydozen.model.Tweak;
import org.nutritionfacts.dailydozen.model.TweakServings;
import org.nutritionfacts.dailydozen.model.Weights;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.Map;

import hugo.weaving.DebugLog;
import timber.log.Timber;

public class RestoreTaskJSON extends TaskWithContext<Uri, Integer, Boolean> {
    private ArrayMap<String, Food> foodLookup;
    private ArrayMap<String, Tweak> tweakLookup;

    public RestoreTaskJSON(Context context) {
        super(context);
        foodLookup = new ArrayMap<>();
        tweakLookup = new ArrayMap<>();
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();

        progress.setTitle(R.string.task_restore_title);
        progress.show();
    }

    @Override
    protected Boolean doInBackground(Uri... params) {
        try {
            final ContentResolver contentResolver = getContext().getContentResolver();

            if (params != null && params.length > 0) {
                InputStream restoreInputStream = contentResolver.openInputStream(params[0]);

                if (restoreInputStream != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(restoreInputStream));

                    final LineNumberReader lineNumberReader = new LineNumberReader(reader);
                    lineNumberReader.skip(Integer.MAX_VALUE);
                    final int numLines = lineNumberReader.getLineNumber() + 1;
                    lineNumberReader.close();

                    // Need to recreate the InputStream and BufferedReader after closing LineNumberReader
                    final InputStream inputStream = contentResolver.openInputStream(params[0]);

                    if (inputStream != null) {
                        // Only delete all existing data if we are sure we have an input stream
                        deleteAllExistingData();

                        reader = new BufferedReader(new InputStreamReader(inputStream));

                        String line = reader.readLine();
                        if (line != null) {
                            int i = 0;

                            do {
                                if (!isCancelled()) {
                                    line = reader.readLine();

                                    if (!TextUtils.isEmpty(line)) {
                                        restoreLine(line);
                                    }

                                    publishProgress(++i, numLines);
                                }
                            } while (line != null);
                        }

                        reader.close();
                        restoreInputStream.close();
                    }

                    return !isCancelled();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    @DebugLog
    private void deleteAllExistingData() {
        Common.truncateAllDatabaseTables();
    }

    @DebugLog
    private void restoreLine(String line) {
        ActiveAndroid.beginTransaction();

        try {
            DayEntries dayEntries = new Gson().fromJson(line, DayEntries.class);

            final Day day = Day.createDayIfDoesNotExist(dayEntries.getDate());

            Weights.createWeightsIfDoesNotExist(day,
                    dayEntries.getMorningWeight(),
                    dayEntries.getEveningWeight());

            for (Map.Entry<String, Integer> entry : dayEntries.getDailyDozen().entrySet()) {
                DDServings.createServingsIfDoesNotExist(day, getFoodByIdName(entry.getKey()), entry.getValue());
            }

            for (Map.Entry<String, Integer> entry : dayEntries.getTweaks().entrySet()) {
                TweakServings.createServingsIfDoesNotExist(day, getTweakByIdName(entry.getKey()), entry.getValue());
            }

            ActiveAndroid.setTransactionSuccessful();
        } catch (InvalidDateException e) {
            Timber.e(e, "restoreLine: ");
        } finally {
            ActiveAndroid.endTransaction();
        }
    }

    private Food getFoodByIdName(String foodIdName) {
        if (!foodLookup.containsKey(foodIdName)) {
            foodLookup.put(foodIdName, Food.getByNameOrIdName(foodIdName));
        }

        return foodLookup.get(foodIdName);
    }

    private Tweak getTweakByIdName(String tweakIdName) {
        if (!tweakLookup.containsKey(tweakIdName)) {
            tweakLookup.put(tweakIdName, Tweak.getByNameOrIdName(tweakIdName));
        }

        return tweakLookup.get(tweakIdName);
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        super.onProgressUpdate(values);

        if (values.length == 2) {
            progress.setProgress(values[0]);
            progress.setMax(values[1]);
        }
    }

    @Override
    protected void onPostExecute(Boolean success) {
        super.onPostExecute(success);
        Bus.restoreCompleteEvent(success);
    }
}
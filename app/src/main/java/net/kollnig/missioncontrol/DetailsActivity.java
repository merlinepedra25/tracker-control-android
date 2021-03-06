/*
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.opencsv.CSVWriter;

import net.kollnig.missioncontrol.data.Database;
import net.kollnig.missioncontrol.data.PlayStore;
import net.kollnig.missioncontrol.main.AppsListAdapter;
import net.kollnig.missioncontrol.main.SettingsActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NavUtils;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;
import androidx.viewpager.widget.ViewPager;

import static net.kollnig.missioncontrol.main.AppsFragment.savePrefs;

public class DetailsActivity extends AppCompatActivity {
	public static final String INTENT_EXTRA_APP_PACKAGENAME = "INTENT_APP_PACKAGENAME";
	public static final String INTENT_EXTRA_APP_UID = "INTENT_APP_UID";
	public static final String INTENT_EXTRA_APP_NAME = "INTENT_APP_NAME";
	public static final int MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 1;
	public static PlayStore.AppInfo app = null;
	private final String TAG = DetailsActivity.class.getSimpleName();
	Set<OnAppInfoLoadedListener> listeners = new HashSet<>();
	File exportDir = new File(
			Environment.getExternalStorageDirectory(), "trackercontrol");
	private Integer appUid;
	private String appPackageName;
	private String appName;
	public static Boolean contactGoogle;

	@Override
	protected void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_details);

		// Receive about details
		Intent intent = getIntent();
		appPackageName = intent.getStringExtra(INTENT_EXTRA_APP_PACKAGENAME);
		appUid = intent.getIntExtra(INTENT_EXTRA_APP_UID, -1);
		appName = intent.getStringExtra(INTENT_EXTRA_APP_NAME);

		// Check if consent to contact external servers
		SharedPreferences settingsPref = PreferenceManager.getDefaultSharedPreferences(this);
		contactGoogle = settingsPref.getBoolean
				(SettingsActivity.KEY_PREF_GOOGLEPLAY_SWITCH, false);

		// Set up paging
		DetailsPagesAdapter detailsPagesAdapter =
				new DetailsPagesAdapter(this,
						getSupportFragmentManager(),
						appPackageName, // Common.getAppName(getPackageManager(), appUid)
						appName);
		ViewPager viewPager = findViewById(R.id.view_pager);
		viewPager.setAdapter(detailsPagesAdapter);
		TabLayout tabs = findViewById(R.id.tabs);
		tabs.setupWithViewPager(viewPager);

		// set toolbar and back arrow
		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		// Set title
		getSupportActionBar().setDisplayShowTitleEnabled(false);
		toolbar.setTitle(getString(R.string.app_info));
		toolbar.setSubtitle(appName);

		// Load PlayStore Data if consent
		if (contactGoogle) {
			new Thread(() -> {
				app = PlayStore.getInfo(appPackageName);
				runOnUiThread(() -> {
					for (OnAppInfoLoadedListener listener : listeners) {
						listener.appInfoLoaded();
					}
				});
			}).start();
		}
	}

	@Override
	public boolean onCreateOptionsMenu (Menu menu) {
		getMenuInflater().inflate(R.menu.menu_details, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected (MenuItem item) {
		switch (item.getItemId()) {
			// Respond to the action bar's Up/Home button
			case android.R.id.home:
				NavUtils.navigateUpFromSameTask(this);
				return true;
			case R.id.action_export_csv:
				if (hasPermissions()) {
					exportCsv();
				}
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	public void addListener (OnAppInfoLoadedListener l) {
		listeners.add(l);
	}

	public void removeListener (OnAppInfoLoadedListener l) {
		listeners.remove(l);
	}

	@Override
	public void onPause () {
		super.onPause();
		savePrefs(this);
	}

	public boolean hasPermissions () {
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
				== PackageManager.PERMISSION_GRANTED)
			return true;

		ActivityCompat.requestPermissions(this,
				new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
				MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
		return false;
	}

	public void exportCsv () {
		if (!exportDir.exists()) {
			try {
				if (!exportDir.mkdir())
					Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
				return;
			} catch (SecurityException ecp) {
				Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
				return;
			}
		}

		new ExportDatabaseCSVTask().execute();
	}

	@Override
	public void onRequestPermissionsResult (int requestCode,
	                                        String[] permissions, int[] grantResults) {
		switch (requestCode) {
			case MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE: {
				// If request is cancelled, the result arrays are empty.
				if (grantResults.length > 0
						&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					exportCsv();
				} else {
					Toast.makeText(this, "Access to files required..", Toast.LENGTH_SHORT).show();
				}
				return;
			}
		}
	}

	private void shareExport () {
		String fileName = appPackageName + ".csv";
		File sharingFile = new File(exportDir, fileName);
		Uri uri = FileProvider.getUriForFile(DetailsActivity.this,
				getApplicationContext().getPackageName() + ".fileprovider",
				sharingFile);

		Intent shareIntent = new Intent(android.content.Intent.ACTION_SEND);
		shareIntent.setType("application/csv");
		shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
		shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivity(Intent.createChooser(shareIntent, "Share CSV"));
	}

	/**
	 * Communicate with fragments through interface.
	 */
	public interface OnAppInfoLoadedListener {
		void appInfoLoaded ();
	}

	class ExportDatabaseCSVTask extends AsyncTask<String, Void, Boolean> {
		private final ProgressDialog dialog = new ProgressDialog(DetailsActivity.this);
		Database database;

		@Override
		protected void onPreExecute () {
			this.dialog.setMessage(getString(R.string.exporting));
			this.dialog.show();
			database = Database.getInstance(DetailsActivity.this);
		}

		protected Boolean doInBackground (final String... args) {
			if (exportDir == null) return false;

			File file = new File(exportDir, appPackageName + ".csv");
			try {
				file.createNewFile();
				CSVWriter csv = new CSVWriter(new FileWriter(file),
						CSVWriter.DEFAULT_SEPARATOR,
						CSVWriter.DEFAULT_QUOTE_CHARACTER,
						CSVWriter.DEFAULT_ESCAPE_CHARACTER,
						CSVWriter.RFC4180_LINE_END);

				Cursor data = database.getAppInfo(appPackageName); // Common.getAppName(getPackageManager(), appUid)
				if (data == null) return false;

				csv.writeNext(data.getColumnNames());
				while (data.moveToNext()) {
					String[] row = new String[data.getColumnNames().length];
					for (int i = 0; i < data.getColumnNames().length; i++) {
						row[i] = data.getString(i);
					}
					csv.writeNext(row);
				}
				csv.close();
				data.close();
			} catch (IOException e) {
				return false;
			}

			return true;
		}

		protected void onPostExecute (final Boolean success) {
			if (this.dialog.isShowing()) {
				this.dialog.dismiss();
			}

			if (!success) {
				Toast.makeText(DetailsActivity.this, R.string.export_failed, Toast.LENGTH_SHORT).show();
				return;
			}

			// Export successul, ask user to further share file!
			View v = findViewById(R.id.view_pager);
			Snackbar s = Snackbar.make(v, R.string.exported, Snackbar.LENGTH_LONG);
			s.setAction(R.string.share_csv, v1 -> shareExport());
			s.setActionTextColor(getResources().getColor(R.color.colorPrimary));
			s.show();
		}
	}
}
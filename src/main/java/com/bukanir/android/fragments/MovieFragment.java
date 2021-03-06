package com.bukanir.android.fragments;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.app.Fragment;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bukanir.android.activities.PlayerActivity;
import com.bukanir.android.application.Settings;
import com.bukanir.android.clients.BukanirClient;
import com.bukanir.android.R;
import com.bukanir.android.entities.Summary;
import com.bukanir.android.helpers.Storage;
import com.nostra13.universalimageloader.cache.disc.impl.UnlimitedDiskCache;
import com.nostra13.universalimageloader.core.DisplayImageOptions;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.bukanir.android.clients.Torrent2HttpClient;
import com.bukanir.android.entities.Movie;
import com.bukanir.android.entities.Subtitle;
import com.bukanir.android.entities.TorrentFile;
import com.bukanir.android.entities.TorrentStatus;
import com.bukanir.android.services.Torrent2HttpService;
import com.bukanir.android.helpers.Utils;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;
import com.nostra13.universalimageloader.core.listener.SimpleImageLoadingListener;

import java.io.File;
import java.util.ArrayList;

public class MovieFragment extends Fragment implements View.OnClickListener {

    public static final String TAG = "MovieFragment";

    Movie movie;
    Summary summary;

    private Settings settings;

    ArrayList<Subtitle> subtitles;

    Button buttonWatch;
    Button buttonTrailer;

    ProgressBar torrentProgressBar;
    TextView downloadingText;
    ProgressBar progressBar;

    TrailerTask trailerTask;
    Torrent2HttpTask torrent2HttpTask;

    DisplayImageOptions options;
    ImageLoader imageLoader = ImageLoader.getInstance();
    ImageLoadingListener animateFirstListener = new SimpleImageLoadingListener();

    public static MovieFragment newInstance(Movie movie, Summary summary) {
        MovieFragment fragment = new MovieFragment();
        Bundle args = new Bundle();
        args.putParcelable("movie", movie);
        args.putParcelable("summary", summary);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView");
        if(savedInstanceState != null) {
            movie = savedInstanceState.getParcelable("movie");
            summary = savedInstanceState.getParcelable("summary");
        } else {
            movie = getArguments().getParcelable("movie");
            summary = getArguments().getParcelable("summary");
        }

        settings = new Settings(getActivity());

        View rootView = inflater.inflate(R.layout.fragment_movie, container, false);

        torrentProgressBar = (ProgressBar) rootView.findViewById(R.id.progressBar);
        torrentProgressBar.setVisibility(View.INVISIBLE);

        downloadingText = (TextView) rootView.findViewById(R.id.downloading);
        downloadingText.setVisibility(View.INVISIBLE);

        buttonWatch = (Button) rootView.findViewById(R.id.watch);
        buttonTrailer = (Button) rootView.findViewById(R.id.trailer);

        buttonWatch.setEnabled(true);
        buttonWatch.setOnClickListener(this);

        if(summary.video != null && !summary.video.isEmpty()) {
            buttonTrailer.setEnabled(true);
            buttonTrailer.setOnClickListener(this);
        } else {
            buttonTrailer.setVisibility(View.INVISIBLE);
        }

        options = new DisplayImageOptions.Builder()
                .showImageOnLoading(R.drawable.ic_stub)
                .showImageForEmptyUri(R.drawable.ic_empty)
                .showImageOnFail(R.drawable.ic_error)
                .cacheOnDisk(true)
                .build();

        if(!imageLoader.isInited()) {
            File imagesDir = new File(getActivity().getCacheDir().toString() + File.separator + "images");
            imagesDir.mkdirs();
            ImageLoaderConfiguration config = new
                ImageLoaderConfiguration.Builder(getActivity().getApplicationContext())
                .diskCache(new UnlimitedDiskCache(imagesDir))
                .defaultDisplayImageOptions(DisplayImageOptions.createSimple())
                .build();
            imageLoader.init(config);
        }

        return rootView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        progressBar = (ProgressBar) view.getRootView().findViewById(R.id.progressbar);
        setMovieText(view);

        ImageView image = (ImageView) view.findViewById(R.id.image);
        imageLoader.displayImage(movie.posterLarge, image, options, animateFirstListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if(Utils.isTorrentServiceRunning(getActivity())) {
            getActivity().stopService(new Intent(getActivity(), Torrent2HttpService.class));
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        Log.d(TAG, "onSaveInstanceState");
        super.onSaveInstanceState(outState);
        if(movie != null && summary != null) {
            outState.putParcelable("movie", movie);
            outState.putParcelable("summary", summary);
        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();

        cancelTorrent2HttpTask();
        cancelTrailerTask();
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        if(progressBar != null) {
           progressBar.setVisibility(View.GONE);
        }

        buttonWatch.setEnabled(true);
        buttonTrailer.setEnabled(true);

        if(Utils.isTorrentServiceRunning(getActivity())) {
            getActivity().stopService(new Intent(getActivity(), Torrent2HttpService.class));
        }
    }

    @Override
    public void onClick(View view) {
        if(view.getId() == R.id.watch) {
            String storage = Storage.getStorage(getActivity());
            if(Storage.isFreeSpaceAvailable(storage, Long.valueOf(movie.size))) {
                view.setEnabled(false);
                startTorrent2HttpTask();
            } else {
                Toast.makeText(getActivity(), getString(R.string.freespace_not_available), Toast.LENGTH_LONG).show();
            }
        } else if(view.getId() == R.id.trailer) {
            view.setEnabled(false);
            startTrailerTask();
        }
    }

    public void startTorrent2HttpTask() {
        Intent intent = new Intent(getActivity(), Torrent2HttpService.class);
        intent.putExtra("magnet", movie.magnetLink);
        getActivity().startService(intent);

        torrent2HttpTask = new Torrent2HttpTask();
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            torrent2HttpTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            torrent2HttpTask.execute();
        }
    }

    public void startTrailerTask() {
        trailerTask = new TrailerTask();
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            trailerTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            trailerTask.execute();
        }
    }

    public void cancelTorrent2HttpTask() {
        if(torrent2HttpTask != null) {
            if(torrent2HttpTask.getStatus().equals(AsyncTask.Status.RUNNING)) {
                torrent2HttpTask.cancel(true);
                torrentProgressBar.setVisibility(View.INVISIBLE);
                downloadingText.setVisibility(View.INVISIBLE);
            }
        }
    }

    public void cancelTrailerTask() {
        if(trailerTask != null) {
            if(trailerTask.getStatus().equals(AsyncTask.Status.RUNNING)) {
                trailerTask.cancel(true);
            }
        }
    }

    public void setMovieText(View rootView) {
        TextView title = (TextView) rootView.findViewById(R.id.title);
        TextView info = (TextView) rootView.findViewById(R.id.info);
        TextView genre = (TextView) rootView.findViewById(R.id.genre);
        TextView info2 = (TextView) rootView.findViewById(R.id.info2);
        TextView cast = (TextView) rootView.findViewById(R.id.cast);
        TextView director = (TextView) rootView.findViewById(R.id.director);
        TextView tagline = (TextView) rootView.findViewById(R.id.tagline);
        TextView overview = (TextView) rootView.findViewById(R.id.overview);

        if(movie == null || summary == null) {
            return;
        }

        title.setText(Utils.toTitleCase(movie.title));
        overview.setText(summary.overview);

        if(summary.cast != null) {
            String castText;
            if(summary.cast.size() >= 4) {
                castText = android.text.TextUtils.join(", ", summary.cast.subList(0, 4));
                if(summary.cast.size() > 4) {
                    castText += "...";
                }
            } else {
                castText = android.text.TextUtils.join(", ", summary.cast);
            }
            Spanned text = Html.fromHtml("<i>"+getString(R.string.cast_description)+"</i>" + castText);
            cast.setText(text);
        } else {
            cast.setVisibility(View.GONE);
        }

        if(summary.genre != null && !summary.genre.isEmpty()) {
            String genreText;
            if(summary.genre.size() > 3) {
                genreText = android.text.TextUtils.join(", ", summary.genre.subList(0, 3));
            } else {
                genreText = android.text.TextUtils.join(", ", summary.genre);
            }
            genre.setText(genreText);
        } else {
            genre.setVisibility(View.GONE);
        }

        if(!summary.director.isEmpty()) {
            Spanned text = Html.fromHtml("<i>"+getString(R.string.director_description)+"</i>" + summary.director);
            director.setText(text);
        } else {
            director.setVisibility(View.GONE);
        }

        if(movie.category.equals("205") || movie.category.equals("208")) {
            int season = Integer.valueOf(movie.season);
            int episode = Integer.valueOf(movie.episode);
            if(season != 0 && episode != 0) {
                tagline.setText(String.format("S%02dE%02d", season, episode));
            } else {
                tagline.setVisibility(View.GONE);
            }
        } else {
            if (!summary.tagline.isEmpty()) {
                tagline.setText(summary.tagline);
            } else {
                tagline.setVisibility(View.GONE);
            }
        }

        String year = "";
        if(!movie.year.isEmpty()) {
            year = String.format("(%s)  ", movie.year);
        }
        String rating = "";
        if(!summary.rating.equals("0.0") && !summary.rating.equals("0") && !summary.rating.isEmpty()) {
            rating = String.format("%s / 10  ", summary.rating);
        }
        String runtime = "";
        if(!summary.runtime.equals("0") && !summary.runtime.isEmpty()) {
            runtime = String.format("%s min  ", summary.runtime);
        }
        String size = "";
        if(!movie.sizeHuman.isEmpty()) {
            size = movie.sizeHuman;
        }
        if(!runtime.isEmpty() && !size.isEmpty()) {
            runtime += "/ ";
        }

        String infoText = rating + year;
        String infoText2 = runtime + size;

        info.setText(infoText);
        info2.setText(infoText2);
    }

    private class Torrent2HttpTask extends AsyncTask<Void, Integer, TorrentFile> {

        protected void onPreExecute() {
            super.onPreExecute();

            torrentProgressBar.setProgress(0);
            torrentProgressBar.setVisibility(View.VISIBLE);
            torrentProgressBar.setMax(100);
            downloadingText.setVisibility(View.VISIBLE);

            if(progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
            }
        }

        protected TorrentFile doInBackground(Void... params) {

            Torrent2HttpClient t2h = new Torrent2HttpClient();
            boolean startup = t2h.waitStartup();
            if(!startup) {
                return null;
            }

            if(isCancelled()) {
                return null;
            }

            int required = 16;
            boolean ready = false;
            while(!ready) {
                TorrentStatus status = t2h.getStatus();
                if(status != null && Integer.parseInt(status.state) >= 3 && !ready) {
                    int downloaded = Integer.parseInt(status.total_download) / (1024*1024);
                    Float percent = (float) downloaded / (float) required * 100;
                    publishProgress(
                            percent.intValue(),
                            Integer.parseInt(status.state),
                            (int) Float.parseFloat(status.download_rate),
                            (int) Float.parseFloat(status.upload_rate),
                            Integer.parseInt(status.num_seeds),
                            Integer.parseInt(status.num_peers)
                            );
                    if(downloaded >= required) {
                        ready = true;
                        break;
                    }
                } else if(status != null) {
                    publishProgress(
                            0,
                            Integer.parseInt(status.state)
                            );
                }

                if(isCancelled()) {
                    break;
                }

                try {
                    Thread.sleep(t2h.T2H_POLL);
                } catch(InterruptedException e) {
                }
            }

            if(isCancelled()) {
                return null;
            }

            if(settings.subtitles()) {
                publishProgress(100, -1);
                subtitles = BukanirClient.getSubtitles(
                        movie.title,
                        movie.year,
                        movie.release,
                        settings.subtitleLanguage(),
                        movie.category,
                        movie.season,
                        movie.episode,
                        summary.imdbId);
            }

            return t2h.getLargestFile();
        }

        protected void onProgressUpdate(Integer... progress) {
            super.onProgressUpdate(progress[0]);
            torrentProgressBar.setProgress(progress[0]);
            int state = progress[1];
            if(state == 0) {
                downloadingText.setText(getString(R.string.queued));
            } else if(state == 1) {
                downloadingText.setText(getString(R.string.checking));
            } else if(state == 2) {
                downloadingText.setText(getString(R.string.downloading_metadata));
            } else if(state == 3 && progress[0] == 0) {
                downloadingText.setText(getString(R.string.downloading));
            } else if(state >= 3) {
                String status = String.format(
                        "D:%dk U:%dk S:%d P:%d",
                        progress[2], progress[3], progress[4], progress[5]);
                downloadingText.setText(status);
            } else if(state == -1) {
                downloadingText.setText(getString(R.string.fetching_subtitles));
            }
        }

        protected void onPostExecute(TorrentFile torrentFile) {
            buttonWatch.setEnabled(true);
            torrentProgressBar.setVisibility(View.INVISIBLE);
            downloadingText.setText("");
            downloadingText.setVisibility(View.INVISIBLE);

            if(progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }

            Intent intent = new Intent(getActivity(), PlayerActivity.class);
            if(torrentFile != null) {
                intent.putExtra("torrent-file", torrentFile);
            }
            if(subtitles != null) {
                intent.putExtra("subtitles", subtitles);
            }
            if(movie != null) {
                intent.putExtra("movie", movie);
            }

            startActivity(intent);
        }

    }

    private class TrailerTask extends AsyncTask<Void, Void, String> {

        protected void onPreExecute() {
            super.onPreExecute();
            if(progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
            }
        }

        protected String doInBackground(Void... params) {
            String result = BukanirClient.getTrailer(summary.video);

            if(isCancelled()) {
                return null;
            }

            return result;
        }

        protected void onPostExecute(String url) {
            buttonTrailer.setEnabled(true);
            if(progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }

            Intent intent = new Intent(getActivity(), PlayerActivity.class);
            if(url != null && !url.isEmpty() && !url.equals("empty")) {
                intent.putExtra("trailer-id", summary.video);
                intent.putExtra("trailer-url", url);
                if(movie != null) {
                    intent.putExtra("movie", movie);
                    startActivity(intent);
                }
            }
        }

    }

}

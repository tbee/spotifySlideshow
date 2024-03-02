package org.tbee.spotifySlideshow;

import org.apache.hc.core5.http.ParseException;
import org.tbee.tecl.TECL;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.IPlaylistItem;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;
import se.michaelthelin.spotify.model_objects.specification.Image;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.awt.Desktop;
import java.awt.Window;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class SpotifyWebapi extends Spotify {

    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);

    private SpotifyApi spotifyApi = null;

    private Song currentlyPlaying = null;
    private List<Song> nextUp = null;

    private void nextUp(List<Song> nextUp) {
        this.nextUp = nextUp;
        nextUpCallback.accept(nextUp);
    }

    private void currentlyPlayingSong(Song song) {
        currentlyPlaying = song;
        currentlyPlayingCallback.accept(song);
    }


    public Spotify connect() {
        try {
            TECL tecl = SpotifySlideshow.tecl();
            TECL webapiTecl = tecl.grp("/spotify/webapi");

            // Setup the API
            String clientId = webapiTecl.str("clientId", "");
            String clientSecret = webapiTecl.str("clientSecret", "");
            spotifyApi = new SpotifyApi.Builder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRedirectUri(new URI(webapiTecl.str("redirect", "")))
                    .build();

            // Do we have tokens stored or need to fetch them?
            String accessToken;
            String refreshToken = webapiTecl.str("refreshToken", "");
            if (!refreshToken.isBlank()) {
                spotifyApi.setRefreshToken(refreshToken);
                AuthorizationCodeCredentials authorizationCodeCredentials = spotifyApi.authorizationCodeRefresh().build().execute();
                accessToken = authorizationCodeCredentials.getAccessToken();
            }
            else {

                // https://developer.spotify.com/documentation/web-api/concepts/authorization
                // The authorizationCodeUri must be opened in the browser, the resulting code (in the redirect URL) pasted into the popup
                // The code can only be used once to connect
                URI authorizationCodeUri = spotifyApi.authorizationCodeUri()
                        .scope("user-read-playback-state,user-read-currently-playing")
                        .build().execute();
                System.out.println("authorizationCodeUri " + authorizationCodeUri);
                Desktop.getDesktop().browse(authorizationCodeUri);

                var authorizationCode = javax.swing.JOptionPane.showInputDialog(Window.getWindows()[0], "Please copy the authorization code here");
                if (authorizationCode == null || authorizationCode.isBlank()) {
                    String message = "Authorization code cannot be empty";
                    javax.swing.JOptionPane.showMessageDialog(Window.getWindows()[0], message);
                    throw new IllegalArgumentException(message);
                }

                AuthorizationCodeCredentials authorizationCodeCredentials = spotifyApi.authorizationCode(authorizationCode).build().execute();
                accessToken = authorizationCodeCredentials.getAccessToken();
                System.out.println("accessToken " + accessToken);
                refreshToken = authorizationCodeCredentials.getRefreshToken();
                System.out.println("refreshToken " + refreshToken);
            }
            spotifyApi.setAccessToken(accessToken);
            spotifyApi.setRefreshToken(refreshToken);

            // Start polling
            scheduledExecutorService.scheduleAtFixedRate(this::pollCurrentlyPlaying, 0, 3, TimeUnit.SECONDS);

            return this;
        }
        catch (IOException | URISyntaxException | SpotifyWebApiException | ParseException e) {
            throw new RuntimeException("Problem connecting to Sportify webapi", e);
        }
    }

    public void pollCurrentlyPlaying() {
        spotifyApi.getUsersCurrentlyPlayingTrack().build().executeAsync()
                .exceptionally(this::logException)
                .thenAccept(currentlyPlaying -> {
                    boolean playing = (currentlyPlaying != null && currentlyPlaying.getIs_playing());
                    Song song = (!playing ? null : new Song(currentlyPlaying.getItem().getId(), "", currentlyPlaying.getItem().getName()));

                    // The artist changes afterward, so we cannot do an equals on the songs
                    String currentlyPlayingId = this.currentlyPlaying == null ? "" : this.currentlyPlaying.id();
                    String songId = song == null ? "" : song.id();
                    boolean songChanged = !Objects.equals(currentlyPlayingId, songId);
                    if (!songChanged) {
                        return;
                    }

                    currentlyPlayingSong(song);

                    if (song == null) {
                        coverArtCallback.accept(null);
                        nextUp(List.of());
                    }
                    else {
                        String id = song.id();
                        pollCovertArt(id);
                        pollNextUp(id);
                        pollArtist(id, track -> updateCurrentlyPlayingArtist(id, track));
                    }
                });
    }

    private void pollArtist(String id, Consumer<Track> callback) {
        spotifyApi.getTrack(id).build().executeAsync()
                .exceptionally(this::logException)
                .thenAccept(track -> callback.accept(track));
    }

    private void updateCurrentlyPlayingArtist(String id, Track track) {
        ifSongIsStillPlaying(id, () -> {
            ArtistSimplified[] artists = track.getArtists();
            if (artists.length == 0) {
                return;
            }
            String name = artists[0].getName();
            Song song = currentlyPlaying.withArtist(name);
            currentlyPlayingSong(song);
        });
    }

    public void pollNextUp(String id) {
        spotifyApi.getTheUsersQueue().build().executeAsync()
                .exceptionally(this::logException)
                .thenAccept(playbackQueue -> {
                    ifSongIsStillPlaying(id, () -> {
                        List<Song> songs = new ArrayList<>();
                        for (IPlaylistItem playlistItem : playbackQueue.getQueue()) {
                            //System.out.println("    | " + playlistItem.getId() + " | \"" + playlistItem.getName() + "\" | # " + playlistItem.getHref());
                            songs.add(new Song(playlistItem.getId(), "", playlistItem.getName()));
                        }
                        nextUp(songs);

                        // Update artist
                        songs.forEach(song -> pollArtist(song.id(), track -> updateNextUpArtist(song.id(), track)));
                    });
                });
    }

    private void updateNextUpArtist(String id, Track track) {
        ArtistSimplified[] artists = track.getArtists();
        if (artists.length == 0) {
            return;
        }
        String name = artists[0].getName();

        synchronized (this) {
            nextUp.stream()
                    .filter(s -> s.id().equals(id))
                    .forEach(s -> {
                        int idx = nextUp.indexOf(s);
                        nextUp.remove(idx);
                        nextUp.add(idx, s.withArtist(name));
                        nextUp(nextUp);
                    });
        }
    }

    public void pollCovertArt(String id) {
        spotifyApi.getTrack(id).build().executeAsync()
                .exceptionally(this::logException)
                .thenAccept(track -> {
                    ifSongIsStillPlaying(id, () -> {
                        try {
                            Image[] images = track.getAlbum().getImages();
                            //Arrays.stream(images).forEach(i -> System.out.println(i.getUrl() + " " + i.getWidth() + "x" + i.getHeight()));
                            coverArtCallback.accept(images.length == 0 ? null : new URL(images[0].getUrl()));
                        } catch (MalformedURLException e) {
                            throw new RuntimeException(e);
                        }
                    });
                });
    }

    private <T> T logException(Throwable t) {
        if (t.getMessage().contains("The access token expired")) {
            try {
                AuthorizationCodeCredentials authorizationCodeCredentials = spotifyApi.authorizationCodeRefresh().build().execute();
                String accessToken = authorizationCodeCredentials.getAccessToken();
                spotifyApi.setAccessToken(accessToken);
                System.out.println("accessToken renewed " + accessToken);
            } catch (IOException | SpotifyWebApiException | ParseException e) {
                e.printStackTrace();
            }
        }
        else {
            t.printStackTrace();
        }
        return null;
    }

    protected void ifSongIsStillPlaying(String id, Runnable runnable) {
        synchronized (this) {
            if (currentlyPlaying != null && currentlyPlaying.id().equals(id)) {
                runnable.run();
            }
        }
    }
}

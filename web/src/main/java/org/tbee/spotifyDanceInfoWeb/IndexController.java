package org.tbee.spotifyDanceInfoWeb;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.apache.hc.core5.http.ParseException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.IPlaylistItem;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.model_objects.specification.ArtistSimplified;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Controller
public class IndexController {

    @GetMapping("/")
    public String index(HttpServletRequest request, Model model) {
        ConnectForm connectForm = new ConnectForm();
        model.addAttribute("ConnectForm", connectForm);

        Cfg cfg = new Cfg();
        if (cfg.webapiRefreshToken() != null) {
            connectForm.setClientId(cfg.webapiClientId());
            connectForm.setClientSecret(cfg.webapiClientSecret());
            connectForm.setRedirectUrl(request.getRequestURL().toString() + "spotifyCallback"); // TBEERNOT generate URL
        }

        return "index";
    }

    @PostMapping("/")
    public String indexSubmit(HttpSession session, Model model, @ModelAttribute ConnectForm connectForm) {
        try {
            SpotifyConnectData spotifyConnectData = spotifyConnectData(session)
                    .clientId(connectForm.getClientId())
                    .clientSecret(connectForm.getClientSecret())
                    .redirectUrl(connectForm.getRedirectUrl());

            // Spotify API
            SpotifyApi spotifyApi = spotifyApi(session);

            // Forward to Spotify
            URI authorizationCodeUri = spotifyApi.authorizationCodeUri()
                    .scope("user-read-playback-state,user-read-currently-playing")
                    .build().execute();
            return "redirect:" + authorizationCodeUri.toURL().toString();
        }
        catch (IOException e) {
            throw new RuntimeException("Problem connecting to Spotify webapi", e);
        }
    }

    @GetMapping("/spotifyCallback")
    public String spotifyCallback(HttpSession session, @RequestParam("code") String authorizationCode) {
        try {
            SpotifyApi spotifyApi = spotifyApi(session);
            AuthorizationCodeCredentials authorizationCodeCredentials = spotifyApi.authorizationCode(authorizationCode).build().execute();

            SpotifyConnectData spotifyConnectData = spotifyConnectData(session);
            spotifyConnectData
                    .refreshToken(authorizationCodeCredentials.getRefreshToken() != null ? authorizationCodeCredentials.getRefreshToken() : spotifyConnectData.refreshToken())
                    .accessToken(authorizationCodeCredentials.getAccessToken());

            return "redirect:/spotify";
        }
        catch (IOException | SpotifyWebApiException | ParseException e) {
            throw new RuntimeException("Problem connecting to Spotify webapi", e);
        }
    }

    @GetMapping("/spotify")
    public String spotify(HttpSession session, Model model) {
        updateCurrentlyPlaying(session);
        model.addAttribute("ScreenData", screenData(session));
        return "spotify";
    }

    private void updateCurrentlyPlaying(HttpSession session) {
        spotifyApi(session).getUsersCurrentlyPlayingTrack().build().executeAsync()
                .exceptionally(t -> logException(session, t))
                .thenAccept(track -> {
                    synchronized (session) {

                        ScreenData screenData = screenData(session);

                        boolean playing = (track != null && track.getIs_playing());
                        Song currentlyPlaying = !playing ? new Song() : new Song(track.getItem().getId(), track.getItem().getName(), "");

                        // The artist changes afterward, so we cannot do an equals on the songs
                        boolean songChanged = !Objects.equals(currentlyPlaying.trackId(), screenData.currentlyPlaying().trackId());
                        if (!songChanged) {
                            return;
                        }

                        screenData.currentlyPlaying(currentlyPlaying);
                        if (currentlyPlaying.trackId().isBlank()) {
                            //coverArtCallback.accept(cfg.waitingImageUrl());
                            screenData.nextUp(List.of());
                        } else {
                            //pollCovertArt(id);
                            pollArtist(session, currentlyPlaying);
                            pollNextUp(session, currentlyPlaying.trackId());
                        }
                    }
                });
    }

    private void pollArtist(HttpSession session, Song song) {
        spotifyApi(session).getTrack(song.trackId()).build().executeAsync()
                .exceptionally(t -> logException(session, t))
                .thenAccept(t -> {
                    ArtistSimplified[] artists = t.getArtists();
                    if (artists.length > 0) {
                        String name = artists[0].getName();
                        song.artist(name);
                    }
                });
    }

    public void pollNextUp(HttpSession session, String trackId) {
        spotifyApi(session).getTheUsersQueue().build().executeAsync()
                .exceptionally(t -> logException(session, t))
                .thenAccept(playbackQueue -> {
                    ScreenData screenData = screenData(session);
                    Song currentlyPlaying = screenData.currentlyPlaying();

                    if (currentlyPlaying != null && currentlyPlaying.trackId().equals(trackId)) {
                        List<Song> songs = new ArrayList<>();
                        for (IPlaylistItem playlistItem : playbackQueue.getQueue()) {
                            //System.out.println("    | " + playlistItem.getId() + " | \"" + playlistItem.getName() + "\" | # " + playlistItem.getHref());
                            songs.add(new Song(playlistItem.getId(), playlistItem.getName(), ""));
                            if (songs.size() == 3) {
                                break; // TBEERNOT
                            }
                        }
                        screenData.nextUp(songs);

                        // Update artist
                        songs.forEach(song -> pollArtist(session, song));
                    }
                });
    }

    private SpotifyApi spotifyApi(HttpSession session) {
        try {
            SpotifyConnectData spotifyConnectData = spotifyConnectData(session);
            return new SpotifyApi.Builder()
                    .setClientId(spotifyConnectData.clientId())
                    .setClientSecret(spotifyConnectData.clientSecret())
                    .setRedirectUri(new URI(spotifyConnectData.redirectUrl()))
                    .setRefreshToken(spotifyConnectData.refreshToken())
                    .setAccessToken(spotifyConnectData.accessToken())
                    .build();
        }
        catch (URISyntaxException e) {
            throw new RuntimeException("Problem connecting to Spotify webapi", e);
        }
    }

    private static SpotifyConnectData spotifyConnectData(HttpSession session) {
        String attributeName = "SpotifyConnectData";
        SpotifyConnectData spotifyConnectData = (SpotifyConnectData) session.getAttribute(attributeName);
        if (spotifyConnectData == null) {
            spotifyConnectData = new SpotifyConnectData();
            session.setAttribute(attributeName, spotifyConnectData);
        }
        return spotifyConnectData;
    }

    private static ScreenData screenData(HttpSession session) {
        String attributeName = "SpotifyScreenData";
        ScreenData screenData = (ScreenData) session.getAttribute(attributeName);
        if (screenData == null) {
            screenData = new ScreenData();
            session.setAttribute(attributeName, screenData);
        }
        return screenData;
    }

    private <T> T logException(HttpSession session, Throwable t) {
        t.printStackTrace();

        // just in case something went wrong with scheduled refreshing
        if (t.getMessage().contains("The access token expired")) {
            refreshAccessToken(session);
        }

        return null;
    }

    private void refreshAccessToken(HttpSession session) {
        try {
            AuthorizationCodeCredentials authorizationCodeCredentials = spotifyApi(session).authorizationCodeRefresh().build().execute();
            SpotifyConnectData spotifyConnectData = spotifyConnectData(session);
            spotifyConnectData
                    .refreshToken(authorizationCodeCredentials.getRefreshToken() != null ? authorizationCodeCredentials.getRefreshToken() : spotifyConnectData.refreshToken())
                    .accessToken(authorizationCodeCredentials.getAccessToken());
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            logException(session, e);
        }
    }

    public static class ConnectForm {
        private String clientId;
        private String clientSecret;
        private String redirectUrl;
        private String refreshToken;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUrl() {
            return redirectUrl;
        }

        public void setRedirectUrl(String redirectUrl) {
            this.redirectUrl = redirectUrl;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }
}

package org.tbee.spotifySlideshow;

import de.labystudio.spotifyapi.SpotifyAPI;
import de.labystudio.spotifyapi.SpotifyAPIFactory;
import de.labystudio.spotifyapi.SpotifyListener;
import de.labystudio.spotifyapi.model.Track;
import org.apache.hc.core5.http.ParseException;
import org.jdesktop.swingx.StackLayout;
import org.tbee.sway.SFrame;
import org.tbee.sway.SLabel;
import org.tbee.sway.SLookAndFeel;
import org.tbee.tecl.TECL;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.IPlaylistItem;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlaying;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SpotifySlideshow {

    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);
    private SpotifyWebapi spotifyWebapi;
    private SpotifyAPI spotifyLocalApi;

    private SLabel sImageLabel;
    private SLabel sTextLabel;
    private SLabel sNextTextLabel;
    private SFrame sFrame;

    // Remember last settings to be able to refresh
    private boolean playing = false;
    private Song song = null;
    private Song nextSong = null;
    private String logline = "";

    public static void main(String[] args) {
        new SpotifySlideshow().run();
    }

    public static TECL tecl() {
        try {
            TECL tecl = TECL.parser().findAndParse();
            return tecl;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void run() {
        SLookAndFeel.installDefault();

        try {
            SwingUtilities.invokeAndWait(() -> {
                sImageLabel = SLabel.of();

                // https://stackoverflow.com/questions/68461904/jlabel-text-shadow
                sTextLabel = SLabel.of();
                sTextLabel.setVerticalAlignment(SwingConstants.TOP);
                sTextLabel.setHorizontalAlignment(SwingConstants.CENTER);
                sTextLabel.setForeground(Color.WHITE);
                sTextLabel.setFont(new Font("Verdana", Font.PLAIN, 80));

                sNextTextLabel = SLabel.of();
                sNextTextLabel.setVerticalAlignment(SwingConstants.BOTTOM);
                sNextTextLabel.setHorizontalAlignment(SwingConstants.CENTER);
                sNextTextLabel.setForeground(Color.WHITE);
                sNextTextLabel.setFont(new Font("Verdana", Font.PLAIN, 40));

                JPanel stackPanel = new JPanel(new StackLayout());
                stackPanel.add(sImageLabel);
                stackPanel.add(sNextTextLabel);
                stackPanel.add(sTextLabel);

                sFrame = SFrame.of(stackPanel)
                        .exitOnClose()
                        .maximize()
                        .undecorated()
                        .visible(true);
                sFrame.addPropertyChangeListener("graphicsConfiguration", e -> updateScreen());
            });
        }
        catch (InterruptedException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        // Load initial image
        URL waitingUrl = getClass().getResource("/waiting.jpg");
        ImageIcon waitingIcon = readAndResizeImage(waitingUrl);
        sImageLabel.setIcon(waitingIcon);

        // And go
        String CONNECT_LOCAL = "local";
        String connect = tecl().str("/spotify/connect", CONNECT_LOCAL);
        if (CONNECT_LOCAL.equalsIgnoreCase(connect)) {
            startSpotifyLocalApi();
        }
        else {
            startSpotifyWebapi();
        }
    }

    private void startSpotifyWebapi() {
        // Connect to spotify
        spotifyWebapi = new SpotifyWebapi(tecl().bool("/webapi/simulate", false));
        spotifyWebapi.connect();

        // Start polling
        scheduledExecutorService.scheduleAtFixedRate(this::pollSpotifyWebapiAndUpdateScreen, 0, 3, TimeUnit.SECONDS);
    }

    private void pollSpotifyWebapiAndUpdateScreen() {
        try {
            CurrentlyPlaying currentlyPlaying = spotifyWebapi.getUsersCurrentlyPlayingTrack();
            if (currentlyPlaying == null || !currentlyPlaying.getIs_playing()) {
                updateScreen(false, null, null);
            }
            else {
                IPlaylistItem item = currentlyPlaying.getItem();
                Song song = new Song(item.getId(), "", item.getName());

                boolean songChanges = (this.song == null || !this.song.id().equals(song.id()));
                if (songChanges) {
                    this.nextSong = null;
                    scheduledExecutorService.schedule(this::pollSpotifyWebapiAndUpdateNextSong, 1, TimeUnit.SECONDS);
//                    scheduledExecutorService.schedule(this::pollSpotifyWebapiAndUpdateImage, 1, TimeUnit.SECONDS);
                }
                updateScreen(true, song, this.nextSong);
            }
        }
        catch (RuntimeException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(sImageLabel, e.getMessage(), "Oops", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void pollSpotifyWebapiAndUpdateNextSong() {
        try {
            spotifyWebapi.getPlaybackQueue(songs -> {
                this.nextSong = (songs.isEmpty() ? null : songs.get(0));
                if (this.nextSong != null) {
                    System.out.println(logline(this.nextSong, "undefined"));
                }
                updateScreen();
            });
        }
        catch (RuntimeException | IOException | ParseException | SpotifyWebApiException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(sImageLabel, e.getMessage(), "Oops", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void pollSpotifyWebapiAndUpdateImage() {
        if (song == null) {
            return;
        }

        try {
            spotifyWebapi.getCoverArt(song.id(), url -> {
                this.sImageLabel.setIcon(readAndResizeImage(url));
                updateScreen();
            });
        }
        catch (RuntimeException | IOException | ParseException | SpotifyWebApiException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(sImageLabel, e.getMessage(), "Oops", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void startSpotifyLocalApi() {
        spotifyLocalApi = SpotifyAPIFactory.create();
        spotifyLocalApi.registerListener(new SpotifyListener() {
            @Override
            public void onConnect() {
            }

            @Override
            public void onTrackChanged(Track track) {
                updateScreen(true, new Song(track.getId(), track.getArtist(), track.getName()), null);
            }

            @Override
            public void onPositionChanged(int position) { }

            @Override
            public void onPlayBackChanged(boolean isPlaying) {
                updateScreen(isPlaying, song, nextSong);
            }

            @Override
            public void onSync() { }

            @Override
            public void onDisconnect(Exception exception) {
                exception.printStackTrace();
                spotifyLocalApi.stop();
            }
        });
        spotifyLocalApi.initialize();
    }

    private void updateScreen() {
        updateScreen(playing, song, nextSong);
    }

    private void updateScreen(boolean playing, Song song, Song nextSong) {
        this.playing = playing;
        this.song = song;
        this.nextSong = nextSong;

        try {
            TECL tecl = tecl();
            String undefinedImage = getClass().getResource("/undefined.jpg").toExternalForm();

            // Determine image and text
            String image;
            String text;
            String nextText;
            String logline;
            if (!playing) {
                image = getClass().getResource("/waiting.jpg").toExternalForm();
                text = "";
                nextText = "";
                logline = "Nothing is playing";
            }
            else {
                // Get song data
                {
                    String trackId = song.id();
                    String dance = tecl.grp("/tracks").str("id", trackId, "dance", "undefined");
                    image = tecl.grp("/dances").str("id", dance, "image", undefinedImage);
                    text = tecl.grp("/dances").str("id", dance, "text", "<div>" + song.artist() + "</div><div>" + song.name() + "</div>");
                    logline = logline(song, dance);
                }

                // Get next song data
                {
                    nextText = "";
                    if (nextSong != null) {
                        String nextTrackId = nextSong.id();
                        String nextDance = tecl.grp("/tracks").str("id", nextTrackId, "dance", "undefined");
                        nextText = "Next: " + tecl.grp("/dances").str("id", nextDance, "nextUpText", nextSong.artist() + " " + nextSong.name());
                    }
                }
            }
            if (!logline.equals(this.logline)) {
                System.out.println(logline);
                this.logline = logline;
            }

            // Load image
            URI uri = new URI(image);
            int contentLength = uri.toURL().openConnection().getContentLength();
            if (contentLength == 0) {
                System.out.println("Image not found " + uri);
                uri = new URI(undefinedImage);
            }
            ImageIcon icon = readAndResizeImage(uri.toURL());

            // Update screen
            String textFinal = text;
            String nextTextFinal = nextText;
            SwingUtilities.invokeLater(() -> {
                sImageLabel.setIcon(icon);

                sTextLabel.setText("<html><body>" + textFinal + "</body></html>");
                sNextTextLabel.setText("<html><body>" + nextTextFinal + "</body></html>");
            });
        }
        catch (RuntimeException | URISyntaxException | IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(sImageLabel, e.getMessage(), "Oops", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String logline(Song song, String dance) {
        dance = (dance == null ? "" : dance);
        dance = (dance + "                    ").substring(0, 20);
        String artist = (song.artist().isBlank() ? "" : song.artist() + " - ");
        return "    | " + song.id() + " | " + dance + " | # " + artist + song.name() + " / https://open.spotify.com/track/" + song.id();
    }

    private ImageIcon readAndResizeImage(URL url) {
        try {
            BufferedImage originalImage = ImageIO.read(url);

            Dimension sFrameSize = sFrame.getSize();
            int width = (int) sFrameSize.getWidth();
            int height = (int) sFrameSize.getHeight();
            BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = resizedImage.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(originalImage, 0, 0, width, height, null);
            g2.dispose();

            return new ImageIcon(resizedImage);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
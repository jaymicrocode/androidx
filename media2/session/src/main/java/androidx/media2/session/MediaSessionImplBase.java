/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media2.session;

import static androidx.media2.common.BaseResult.RESULT_ERROR_BAD_VALUE;
import static androidx.media2.common.MediaMetadata.METADATA_KEY_DURATION;
import static androidx.media2.common.MediaMetadata.METADATA_KEY_MEDIA_ID;
import static androidx.media2.common.MediaMetadata.METADATA_KEY_PLAYABLE;
import static androidx.media2.common.SessionPlayer.PLAYER_STATE_IDLE;
import static androidx.media2.common.SessionPlayer.UNKNOWN_TIME;
import static androidx.media2.session.MediaUtils.DIRECT_EXECUTOR;
import static androidx.media2.session.SessionResult.RESULT_ERROR_INVALID_STATE;
import static androidx.media2.session.SessionResult.RESULT_ERROR_SESSION_DISCONNECTED;
import static androidx.media2.session.SessionResult.RESULT_ERROR_UNKNOWN;
import static androidx.media2.session.SessionResult.RESULT_INFO_SKIPPED;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.MediaSessionCompat.Token;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.concurrent.futures.AbstractResolvableFuture;
import androidx.concurrent.futures.ResolvableFuture;
import androidx.core.util.ObjectsCompat;
import androidx.media.AudioAttributesCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media2.common.BaseResult;
import androidx.media2.common.MediaItem;
import androidx.media2.common.MediaMetadata;
import androidx.media2.common.SessionPlayer;
import androidx.media2.common.SessionPlayer.PlayerResult;
import androidx.media2.common.SessionPlayer.TrackInfo;
import androidx.media2.common.SubtitleData;
import androidx.media2.common.VideoSize;
import androidx.media2.session.MediaSession.ControllerCb;
import androidx.media2.session.MediaSession.ControllerInfo;
import androidx.media2.session.MediaSession.SessionCallback;
import androidx.media2.session.SequencedFutureManager.SequencedFuture;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressLint("ObsoleteSdkInt") // TODO: Remove once the minSdkVersion is lowered enough.
class MediaSessionImplBase implements MediaSession.MediaSessionImpl {
    private static final String DEFAULT_MEDIA_SESSION_TAG_PREFIX = "androidx.media2.session.id";
    private static final String DEFAULT_MEDIA_SESSION_TAG_DELIM = ".";
    private static final int ITEM_NONE = -1;

    // Create a static lock for synchronize methods below.
    // We'd better not use MediaSessionImplBase.class for synchronized(), which indirectly exposes
    // lock object to the outside of the class.
    private static final Object STATIC_LOCK = new Object();
    @GuardedBy("STATIC_LOCK")
    private static boolean sComponentNamesInitialized = false;
    @GuardedBy("STATIC_LOCK")
    private static ComponentName sServiceComponentName;

    static final String TAG = "MSImplBase";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final SessionResult RESULT_WHEN_CLOSED = new SessionResult(RESULT_INFO_SKIPPED);

    final Object mLock = new Object();
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Uri mSessionUri;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Executor mCallbackExecutor;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final SessionCallback mCallback;

    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final MediaSessionCompat mSessionCompat;
    private final MediaSessionStub mSessionStub;
    private final MediaSessionLegacyStub mSessionLegacyStub;
    private final String mSessionId;
    private final SessionToken mSessionToken;
    private final AudioManager mAudioManager;
    private final SessionPlayer.PlayerCallback mPlayerCallback;
    private final MediaSession mInstance;
    private final PendingIntent mSessionActivity;
    private final PendingIntent mMediaButtonIntent;
    private final BroadcastReceiver mBroadcastReceiver;

    @GuardedBy("mLock")
    @SuppressWarnings("WeakerAccess") /* synthetic access */
            MediaController.PlaybackInfo mPlaybackInfo;

    @GuardedBy("mLock")
    private SessionPlayer mPlayer;
    @GuardedBy("mLock")
    private MediaBrowserServiceCompat mBrowserServiceLegacyStub;

    MediaSessionImplBase(MediaSession instance, Context context, String id, SessionPlayer player,
            PendingIntent sessionActivity, Executor callbackExecutor, SessionCallback callback,
            Bundle tokenExtras) {
        mContext = context;
        mInstance = instance;
        mHandlerThread = new HandlerThread("MediaSession_Thread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mSessionStub = new MediaSessionStub(this);
        mSessionActivity = sessionActivity;

        mCallback = callback;
        mCallbackExecutor = callbackExecutor;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        mPlayerCallback = new SessionPlayerCallback(this);

        mSessionId = id;
        // Build Uri that differentiate sessions across the creation/destruction in PendingIntent.
        // Here's the reason why Session ID / SessionToken aren't suitable here.
        //   - Session ID
        //     PendingIntent from the previously closed session with the same ID can be sent to the
        //     newly created session.
        //   - SessionToken
        //     SessionToken is a Parcelable so we can only put it into the intent extra.
        //     However, creating two different PendingIntent that only differs extras isn't allowed.
        //     See {@link PendingIntent} and {@link Intent#filterEquals} for details.
        mSessionUri = new Uri.Builder().scheme(MediaSessionImplBase.class.getName()).appendPath(id)
                .appendPath(String.valueOf(SystemClock.elapsedRealtime())).build();
        mSessionToken = new SessionToken(new SessionTokenImplBase(Process.myUid(),
                SessionToken.TYPE_SESSION, context.getPackageName(), mSessionStub, tokenExtras,
                MediaUtils.CURRENT_VERSION));
        String sessionCompatId = TextUtils.join(DEFAULT_MEDIA_SESSION_TAG_DELIM,
                new String[] {DEFAULT_MEDIA_SESSION_TAG_PREFIX, id});

        ComponentName mbrComponent = null;
        synchronized (STATIC_LOCK) {
            if (!sComponentNamesInitialized) {
                sServiceComponentName = getServiceComponentByAction(
                        MediaLibraryService.SERVICE_INTERFACE);
                if (sServiceComponentName == null) {
                    sServiceComponentName = getServiceComponentByAction(
                            MediaSessionService.SERVICE_INTERFACE);
                }
                sComponentNamesInitialized = true;
            }
            mbrComponent = sServiceComponentName;
        }
        if (mbrComponent == null) {
            // No service to revive playback after it's dead.
            // Create a PendingIntent that points to the runtime broadcast receiver.
            Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON, mSessionUri);
            intent.setPackage(context.getPackageName());
            mMediaButtonIntent = PendingIntent.getBroadcast(
                    context, 0 /* requestCode */, intent, 0 /* flags */);

            // Creates a dummy ComponentName for MediaSessionCompat in pre-L.
            // TODO: Replace this with the MediaButtonReceiver class.
            mbrComponent = new ComponentName(context, context.getClass());

            // Create and register a BroadcastReceiver for receiving PendingIntent.
            // TODO: Introduce MediaButtonReceiver in AndroidManifest instead of this,
            //       or register only one receiver for all sessions.
            mBroadcastReceiver = new MediaButtonReceiver();
            IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            filter.addDataScheme(mSessionUri.getScheme());
            context.registerReceiver(mBroadcastReceiver, filter);
        } else {
            // Has MediaSessionService to revive playback after it's dead.
            Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON, mSessionUri);
            intent.setComponent(mbrComponent);
            if (Build.VERSION.SDK_INT >= 26) {
                mMediaButtonIntent = PendingIntent.getForegroundService(mContext, 0, intent, 0);
            } else {
                mMediaButtonIntent = PendingIntent.getService(mContext, 0, intent, 0);
            }
            mBroadcastReceiver = null;
        }

        mSessionCompat = new MediaSessionCompat(context, sessionCompatId, mbrComponent,
                mMediaButtonIntent, mSessionToken.getExtras(), mSessionToken);
        // NOTE: mSessionLegacyStub should be created after mSessionCompat created.
        mSessionLegacyStub = new MediaSessionLegacyStub(this);

        mSessionCompat.setSessionActivity(sessionActivity);
        mSessionCompat.setFlags(MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS);

        updatePlayer(player);
        // Do followings at the last moment. Otherwise commands through framework would be sent to
        // this session while initializing, and end up with unexpected situation.
        mSessionCompat.setCallback(mSessionLegacyStub, mHandler);
        mSessionCompat.setActive(true);
    }

    @Override
    public void updatePlayer(@NonNull SessionPlayer player,
            @Nullable SessionPlayer playlistAgent) {
        // No-op
    }

    // TODO(jaewan): Remove SuppressLint when removing duplication session callback.
    @Override
    @SuppressLint("WrongConstant")
    public void updatePlayer(@NonNull SessionPlayer player) {
        final boolean isPlaybackInfoChanged;

        final SessionPlayer oldPlayer;
        final MediaController.PlaybackInfo info = createPlaybackInfo(player, null);

        synchronized (mLock) {
            isPlaybackInfoChanged = !info.equals(mPlaybackInfo);

            oldPlayer = mPlayer;
            mPlayer = player;
            mPlaybackInfo = info;

            if (oldPlayer != mPlayer) {
                if (oldPlayer != null) {
                    oldPlayer.unregisterPlayerCallback(mPlayerCallback);
                }
                mPlayer.registerPlayerCallback(mCallbackExecutor, mPlayerCallback);
            }
        }

        if (oldPlayer == null) {
            // updatePlayerConnector() is called inside of the constructor.
            // There's no connected controllers at this moment, so just initialize session compat's
            // playback state. Otherwise, framework doesn't know whether this is ready to receive
            // media key event.
            mSessionCompat.setPlaybackState(createPlaybackStateCompat());
        } else {
            if (player != oldPlayer) {
                final int state = getPlayerState();
                mCallbackExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        mCallback.onPlayerStateChanged(getInstance(), state);
                    }
                });
                notifyPlayerUpdatedNotLocked(oldPlayer);
            }
            if (isPlaybackInfoChanged) {
                notifyPlaybackInfoChangedNotLocked(info);
            }
        }

        if (player instanceof RemoteSessionPlayer) {
            final RemoteSessionPlayer remotePlayer = (RemoteSessionPlayer) player;
            VolumeProviderCompat volumeProvider =
                    new VolumeProviderCompat(remotePlayer.getVolumeControlType(),
                            remotePlayer.getMaxVolume(),
                            remotePlayer.getVolume()) {
                        // TODO(b/138091975) Do not ignore the returned Future.
                        @SuppressWarnings("FutureReturnValueIgnored")
                        @Override
                        public void onSetVolumeTo(int volume) {
                            remotePlayer.setVolume(volume);
                        }

                        // TODO(b/138091975) Do not ignore the returned Future.
                        @SuppressWarnings("FutureReturnValueIgnored")
                        @Override
                        public void onAdjustVolume(int direction) {
                            remotePlayer.adjustVolume(direction);
                        }
                    };
            mSessionCompat.setPlaybackToRemote(volumeProvider);
        } else {
            int stream = getLegacyStreamType(player.getAudioAttributes());
            mSessionCompat.setPlaybackToLocal(stream);
        }
    }

    @NonNull
    MediaController.PlaybackInfo createPlaybackInfo(@NonNull SessionPlayer player,
            AudioAttributesCompat audioAttributes) {
        final AudioAttributesCompat attrs = audioAttributes != null ? audioAttributes :
                player.getAudioAttributes();

        if (!(player instanceof RemoteSessionPlayer)) {
            int stream = getLegacyStreamType(attrs);
            int controlType = VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE;
            if (Build.VERSION.SDK_INT >= 21 && mAudioManager.isVolumeFixed()) {
                controlType = VolumeProviderCompat.VOLUME_CONTROL_FIXED;
            }
            return MediaController.PlaybackInfo.createPlaybackInfo(
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL,
                    attrs,
                    controlType,
                    mAudioManager.getStreamMaxVolume(stream),
                    mAudioManager.getStreamVolume(stream));
        } else {
            RemoteSessionPlayer remotePlayer = (RemoteSessionPlayer) player;
            return MediaController.PlaybackInfo.createPlaybackInfo(
                    MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE,
                    attrs,
                    remotePlayer.getVolumeControlType(),
                    remotePlayer.getMaxVolume(),
                    remotePlayer.getVolume());
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    static int getLegacyStreamType(@Nullable AudioAttributesCompat attrs) {
        int stream;
        if (attrs == null) {
            stream = AudioManager.STREAM_MUSIC;
        } else {
            stream = attrs.getLegacyStreamType();
            if (stream == AudioManager.USE_DEFAULT_STREAM_TYPE) {
                // Usually, AudioAttributesCompat#getLegacyStreamType() does not return
                // USE_DEFAULT_STREAM_TYPE unless the developer sets it with
                // AudioAttributesCompat.Builder#setLegacyStreamType().
                // But for safety, let's convert USE_DEFAULT_STREAM_TYPE to STREAM_MUSIC here.
                stream = AudioManager.STREAM_MUSIC;
            }
        }
        return stream;
    }

    @Override
    public void close() {
        synchronized (mLock) {
            if (isClosed()) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Closing session, id=" + getId() + ", token="
                        + getToken());
            }
            mPlayer.unregisterPlayerCallback(mPlayerCallback);
            mSessionCompat.release();
            mMediaButtonIntent.cancel();
            if (mBroadcastReceiver != null) {
                mContext.unregisterReceiver(mBroadcastReceiver);
            }
            mCallback.onSessionClosed(mInstance);
            dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onDisconnected(seq);
                }
            });
            mHandler.removeCallbacksAndMessages(null);
            if (mHandlerThread.isAlive()) {
                if (Build.VERSION.SDK_INT >= 18) {
                    mHandlerThread.quitSafely();
                } else {
                    mHandlerThread.quit();
                }
            }
        }
    }

    @Override
    @NonNull
    public SessionPlayer getPlayer() {
        synchronized (mLock) {
            return mPlayer;
        }
    }

    @Override
    @NonNull
    public String getId() {
        return mSessionId;
    }

    @Override
    @NonNull
    public Uri getUri() {
        return mSessionUri;
    }

    @Override
    @NonNull
    public SessionToken getToken() {
        return mSessionToken;
    }

    @Override
    @NonNull
    public List<ControllerInfo> getConnectedControllers() {
        List<ControllerInfo> controllers = new ArrayList<>();
        controllers.addAll(mSessionStub.getConnectedControllersManager()
                .getConnectedControllers());
        controllers.addAll(mSessionLegacyStub.getConnectedControllersManager()
                .getConnectedControllers());
        return controllers;
    }

    @Override
    public boolean isConnected(@NonNull ControllerInfo controller) {
        if (controller == null) {
            return false;
        }
        if (controller.equals(mSessionLegacyStub.getControllersForAll())) {
            return true;
        }
        return mSessionStub.getConnectedControllersManager().isConnected(controller)
                || mSessionLegacyStub.getConnectedControllersManager().isConnected(controller);
    }

    @Override
    public ListenableFuture<SessionResult> setCustomLayout(@NonNull ControllerInfo controller,
            @NonNull final List<MediaSession.CommandButton> layout) {
        return dispatchRemoteControllerTask(controller, new RemoteControllerTask() {
            @Override
            public void run(ControllerCb controller, int seq) throws RemoteException {
                controller.setCustomLayout(seq, layout);
            }
        });
    }

    @Override
    public void setAllowedCommands(@NonNull ControllerInfo controller,
            @NonNull final SessionCommandGroup commands) {
        if (mSessionStub.getConnectedControllersManager().isConnected(controller)) {
            mSessionStub.getConnectedControllersManager()
                    .updateAllowedCommands(controller, commands);
            dispatchRemoteControllerTaskWithoutReturn(controller, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onAllowedCommandsChanged(seq, commands);
                }
            });
        } else {
            mSessionLegacyStub.getConnectedControllersManager()
                    .updateAllowedCommands(controller, commands);
        }
    }

    @Override
    public void broadcastCustomCommand(@NonNull final SessionCommand command,
            @Nullable final Bundle args) {
        dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
            @Override
            public void run(ControllerCb controller, int seq) throws RemoteException {
                controller.sendCustomCommand(seq, command, args);
            }
        });
    }

    @Override
    public ListenableFuture<SessionResult> sendCustomCommand(
            @NonNull ControllerInfo controller, @NonNull final SessionCommand command,
            @Nullable final Bundle args) {
        return dispatchRemoteControllerTask(controller, new RemoteControllerTask() {
            @Override
            public void run(ControllerCb controller, int seq) throws RemoteException {
                controller.sendCustomCommand(seq, command, args);
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public ListenableFuture<PlayerResult> play() {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                if (player.getPlayerState() != PLAYER_STATE_IDLE) {
                    return player.play();
                }
                final ListenableFuture<PlayerResult> prepareFuture = player.prepare();
                final ListenableFuture<PlayerResult> playFuture = player.play();
                if (prepareFuture == null || playFuture == null) {
                    // Let dispatchPlayerTask() handle such cases.
                    return null;
                }
                return CombinedCommandResultFuture.create(
                        DIRECT_EXECUTOR, prepareFuture, playFuture);
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> pause() {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.pause();
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> prepare() {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.prepare();
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> seekTo(final long pos) {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.seekTo(pos);
            }
        });
    }

    @Override
    @SessionPlayer.PlayerState
    public int getPlayerState() {
        return dispatchPlayerTask(new PlayerTask<Integer>() {
            @Override
            public Integer run(@NonNull SessionPlayer player) throws Exception {
                return player.getPlayerState();
            }
        }, SessionPlayer.PLAYER_STATE_ERROR);
    }

    @Override
    public long getCurrentPosition() {
        return dispatchPlayerTask(new PlayerTask<Long>() {
            @Override
            public Long run(@NonNull SessionPlayer player) throws Exception {
                if (isInPlaybackState(player)) {
                    return player.getCurrentPosition();
                }
                return null;
            }
        }, SessionPlayer.UNKNOWN_TIME);
    }

    @Override
    public long getDuration() {
        return dispatchPlayerTask(new PlayerTask<Long>() {
            @Override
            public Long run(@NonNull SessionPlayer player) throws Exception {
                if (isInPlaybackState(player)) {
                    return player.getDuration();
                }
                return null;
            }
        }, SessionPlayer.UNKNOWN_TIME);
    }

    @Override
    public long getBufferedPosition() {
        return dispatchPlayerTask(new PlayerTask<Long>() {
            @Override
            public Long run(@NonNull SessionPlayer player) throws Exception {
                if (isInPlaybackState(player)) {
                    return player.getBufferedPosition();
                }
                return null;
            }
        }, SessionPlayer.UNKNOWN_TIME);
    }

    @Override
    @SessionPlayer.BuffState
    public int getBufferingState() {
        return dispatchPlayerTask(new PlayerTask<Integer>() {
            @Override
            public Integer run(@NonNull SessionPlayer player) throws Exception {
                return player.getBufferingState();
            }
        }, SessionPlayer.BUFFERING_STATE_UNKNOWN);
    }

    @Override
    public float getPlaybackSpeed() {
        return dispatchPlayerTask(new PlayerTask<Float>() {
            @Override
            public Float run(@NonNull SessionPlayer player) throws Exception {
                if (isInPlaybackState(player)) {
                    return player.getPlaybackSpeed();
                }
                return null;
            }
        }, 1.0f);
    }

    @Override
    public ListenableFuture<PlayerResult> setPlaybackSpeed(final float speed) {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.setPlaybackSpeed(speed);
            }
        });
    }

    @Override
    public List<MediaItem> getPlaylist() {
        return dispatchPlayerTask(new PlayerTask<List<MediaItem>>() {
            @Override
            public List<MediaItem> run(@NonNull SessionPlayer player) throws Exception {
                return player.getPlaylist();
            }
        }, null);
    }

    @Override
    public ListenableFuture<PlayerResult> setPlaylist(@NonNull final List<MediaItem> list,
            @Nullable final MediaMetadata metadata) {
        if (list == null) {
            throw new NullPointerException("list shouldn't be null");
        }
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.setPlaylist(list, metadata);
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> setMediaItem(@NonNull final MediaItem item) {
        if (item == null) {
            throw new NullPointerException("item shouldn't be null");
        }
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.setMediaItem(item);
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> skipToPlaylistItem(final int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                final List<MediaItem> list = player.getPlaylist();
                if (index >= list.size()) {
                    return PlayerResult.createFuture(RESULT_ERROR_BAD_VALUE);
                }
                return player.skipToPlaylistItem(index);
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> skipToPreviousItem() {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.skipToPreviousPlaylistItem();
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> skipToNextItem() {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.skipToNextPlaylistItem();
            }
        });
    }

    @Override
    public MediaMetadata getPlaylistMetadata() {
        return dispatchPlayerTask(new PlayerTask<MediaMetadata>() {
            @Override
            public MediaMetadata run(@NonNull SessionPlayer player) throws Exception {
                return player.getPlaylistMetadata();
            }
        }, null);
    }

    @Override
    public ListenableFuture<PlayerResult> addPlaylistItem(final int index,
            @NonNull final MediaItem item) {
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        if (item == null) {
            throw new NullPointerException("item shouldn't be null");
        }
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.addPlaylistItem(index, item);
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> removePlaylistItem(final int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                final List<MediaItem> list = player.getPlaylist();
                if (index >= list.size()) {
                    return PlayerResult.createFuture(RESULT_ERROR_BAD_VALUE);
                }
                return player.removePlaylistItem(index);
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> replacePlaylistItem(final int index,
            @NonNull final MediaItem item) {
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        if (item == null) {
            throw new NullPointerException("item shouldn't be null");
        }
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.replacePlaylistItem(index, item);
            }
        });

    }

    @Override
    public ListenableFuture<PlayerResult> movePlaylistItem(final int fromIndex, final int toIndex) {
        if (fromIndex < 0 || toIndex < 0) {
            throw new IllegalArgumentException("indices shouldn't be negative");
        }
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.movePlaylistItem(fromIndex, toIndex);
            }
        });
    }

    @Override
    public MediaItem getCurrentMediaItem() {
        return dispatchPlayerTask(new PlayerTask<MediaItem>() {
            @Override
            public MediaItem run(@NonNull SessionPlayer player) throws Exception {
                return player.getCurrentMediaItem();
            }
        }, null);
    }

    @Override
    public int getCurrentMediaItemIndex() {
        return dispatchPlayerTask(new PlayerTask<Integer>() {
            @Override
            public Integer run(@NonNull SessionPlayer player) throws Exception {
                return player.getCurrentMediaItemIndex();
            }
        }, ITEM_NONE);
    }

    @Override
    public int getPreviousMediaItemIndex() {
        return dispatchPlayerTask(new PlayerTask<Integer>() {
            @Override
            public Integer run(@NonNull SessionPlayer player) throws Exception {
                return player.getPreviousMediaItemIndex();
            }
        }, ITEM_NONE);
    }

    @Override
    public int getNextMediaItemIndex() {
        return dispatchPlayerTask(new PlayerTask<Integer>() {
            @Override
            public Integer run(@NonNull SessionPlayer player) throws Exception {
                return player.getNextMediaItemIndex();
            }
        }, ITEM_NONE);
    }

    @Override
    public ListenableFuture<PlayerResult> updatePlaylistMetadata(
            @Nullable final MediaMetadata metadata) {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.updatePlaylistMetadata(metadata);
            }
        });
    }

    @Override
    @SessionPlayer.RepeatMode
    public int getRepeatMode() {
        return dispatchPlayerTask(new PlayerTask<Integer>() {
            @Override
            public Integer run(@NonNull SessionPlayer player) throws Exception {
                return player.getRepeatMode();
            }
        }, SessionPlayer.REPEAT_MODE_NONE);
    }

    @Override
    public ListenableFuture<PlayerResult> setRepeatMode(
            final @SessionPlayer.RepeatMode int repeatMode) {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.setRepeatMode(repeatMode);
            }
        });
    }

    @Override
    @SessionPlayer.ShuffleMode
    public int getShuffleMode() {
        return dispatchPlayerTask(new PlayerTask<Integer>() {
            @Override
            public Integer run(@NonNull SessionPlayer player) throws Exception {
                return player.getShuffleMode();
            }
        }, SessionPlayer.SHUFFLE_MODE_NONE);
    }

    @Override
    public ListenableFuture<PlayerResult> setShuffleMode(
            final @SessionPlayer.ShuffleMode int shuffleMode) {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.setShuffleMode(shuffleMode);
            }
        });
    }

    @Override
    public VideoSize getVideoSize() {
        return dispatchPlayerTask(new PlayerTask<VideoSize>() {
            @Override
            public VideoSize run(@NonNull SessionPlayer player) {
                return MediaUtils.upcastForPreparceling(player.getVideoSize());
            }
        }, new VideoSize(0, 0));
    }

    @Override
    public ListenableFuture<PlayerResult> setSurface(final Surface surface) {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player) {
                return player.setSurface(surface);
            }
        });
    }

    @Override
    public List<TrackInfo> getTracks() {
        return dispatchPlayerTask(new PlayerTask<List<TrackInfo>>() {
            @Override
            public List<TrackInfo> run(@NonNull SessionPlayer player) throws Exception {
                return MediaUtils.upcastForPreparceling(player.getTracks());
            }
        }, null);
    }

    @Override
    public ListenableFuture<PlayerResult> selectTrack(final TrackInfo trackInfo) {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.selectTrack(trackInfo);
            }
        });
    }

    @Override
    public ListenableFuture<PlayerResult> deselectTrack(final TrackInfo trackInfo) {
        return dispatchPlayerTask(new PlayerTask<ListenableFuture<PlayerResult>>() {
            @Override
            public ListenableFuture<PlayerResult> run(@NonNull SessionPlayer player)
                    throws Exception {
                return player.deselectTrack(trackInfo);
            }
        });
    }

    @Override
    public TrackInfo getSelectedTrack(final int trackType) {
        return dispatchPlayerTask(new PlayerTask<TrackInfo>() {
            @Override
            public TrackInfo run(@NonNull SessionPlayer player) throws Exception {
                return MediaUtils.upcastForPreparceling(player.getSelectedTrack(trackType));
            }
        }, null);
    }

    ///////////////////////////////////////////////////
    // package private and private methods
    ///////////////////////////////////////////////////
    @Override
    @NonNull
    public MediaSession getInstance() {
        return mInstance;
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public Executor getCallbackExecutor() {
        return mCallbackExecutor;
    }

    @Override
    public SessionCallback getCallback() {
        return mCallback;
    }

    @Override
    public MediaSessionCompat getSessionCompat() {
        return mSessionCompat;
    }

    @Override
    public boolean isClosed() {
        return !mHandlerThread.isAlive();
    }

    @Override
    public PlaybackStateCompat createPlaybackStateCompat() {
        synchronized (mLock) {
            int state = MediaUtils.convertToPlaybackStateCompatState(getPlayerState(),
                    getBufferingState());
            long allActions = PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_REWIND
                    | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    | PlaybackStateCompat.ACTION_FAST_FORWARD
                    | PlaybackStateCompat.ACTION_SET_RATING
                    | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_PLAY_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                    | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                    | PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
                    | PlaybackStateCompat.ACTION_PLAY_FROM_URI | PlaybackStateCompat.ACTION_PREPARE
                    | PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
                    | PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH
                    | PlaybackStateCompat.ACTION_PREPARE_FROM_URI
                    | PlaybackStateCompat.ACTION_SET_REPEAT_MODE
                    | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
                    | PlaybackStateCompat.ACTION_SET_CAPTIONING_ENABLED;
            return new PlaybackStateCompat.Builder()
                    .setState(state, getCurrentPosition(), getPlaybackSpeed(),
                            SystemClock.elapsedRealtime())
                    .setActions(allActions)
                    .setBufferedPosition(getBufferedPosition())
                    .build();
        }
    }

    @Override
    public MediaController.PlaybackInfo getPlaybackInfo() {
        synchronized (mLock) {
            return mPlaybackInfo;
        }
    }

    @Override
    public PendingIntent getSessionActivity() {
        return mSessionActivity;
    }

    MediaBrowserServiceCompat createLegacyBrowserService(Context context, SessionToken token,
            Token sessionToken) {
        return new MediaSessionServiceLegacyStub(context, this, sessionToken);
    }

    @Override
    public void connectFromService(IMediaController caller, int controllerVersion,
            String packageName, int pid, int uid, @Nullable Bundle connectionHints) {
        mSessionStub.connect(caller, controllerVersion, packageName, pid, uid, connectionHints);
    }

    /**
     * Gets the service binder from the MediaBrowserServiceCompat. Should be only called by the
     * thread with a Looper.
     *
     * @return
     */
    @Override
    public IBinder getLegacyBrowserServiceBinder() {
        MediaBrowserServiceCompat legacyStub;
        synchronized (mLock) {
            if (mBrowserServiceLegacyStub == null) {
                mBrowserServiceLegacyStub = createLegacyBrowserService(mContext, mSessionToken,
                        mSessionCompat.getSessionToken());
            }
            legacyStub = mBrowserServiceLegacyStub;
        }
        Intent intent = new Intent(MediaBrowserServiceCompat.SERVICE_INTERFACE);
        return legacyStub.onBind(intent);
    }

    MediaBrowserServiceCompat getLegacyBrowserService() {
        synchronized (mLock) {
            return mBrowserServiceLegacyStub;
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    boolean isInPlaybackState(@NonNull SessionPlayer player) {
        return !isClosed()
                && player.getPlayerState() != SessionPlayer.PLAYER_STATE_IDLE
                && player.getPlayerState() != SessionPlayer.PLAYER_STATE_ERROR;
    }

    private @Nullable MediaItem getCurrentMediaItemOrNull() {
        final SessionPlayer player;
        synchronized (mLock) {
            player = mPlayer;
        }
        return player != null ? player.getCurrentMediaItem() : null;
    }

    private @Nullable List<MediaItem> getPlaylistOrNull() {
        final SessionPlayer player;
        synchronized (mLock) {
            player = mPlayer;
        }
        return player != null ? player.getPlaylist() : null;
    }

    private ListenableFuture<PlayerResult> dispatchPlayerTask(
            @NonNull PlayerTask<ListenableFuture<PlayerResult>> command) {
        ResolvableFuture<PlayerResult> result = ResolvableFuture.create();
        result.set(new PlayerResult(RESULT_ERROR_INVALID_STATE, null));
        return dispatchPlayerTask(command, result);
    }

    private <T> T dispatchPlayerTask(@NonNull PlayerTask<T> command, T defaultResult) {
        final SessionPlayer player;
        synchronized (mLock) {
            player = mPlayer;
        }
        try {
            if (!isClosed()) {
                T result = command.run(player);
                if (result != null) {
                    return result;
                }
            } else if (DEBUG) {
                Log.d(TAG, "API calls after the close()", new IllegalStateException());
            }
        } catch (Exception e) {
        }
        return defaultResult;
    }

    // TODO(jaewan): Remove SuppressLint when removing duplication session callback.
    @SuppressLint("WrongConstant")
    private void notifyPlayerUpdatedNotLocked(SessionPlayer oldPlayer) {
        // Tells the playlist change first, to current item can change be notified with an item
        // within the playlist.
        List<MediaItem> oldPlaylist = oldPlayer.getPlaylist();
        final List<MediaItem> newPlaylist = getPlaylistOrNull();
        if (!ObjectsCompat.equals(oldPlaylist, newPlaylist)) {
            dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onPlaylistChanged(seq,
                            newPlaylist, getPlaylistMetadata(), getCurrentMediaItemIndex(),
                            getPreviousMediaItemIndex(), getNextMediaItemIndex());
                }
            });
        } else {
            MediaMetadata oldMetadata = oldPlayer.getPlaylistMetadata();
            final MediaMetadata newMetadata = getPlaylistMetadata();
            if (!ObjectsCompat.equals(oldMetadata, newMetadata)) {
                dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                    @Override
                    public void run(ControllerCb callback, int seq) throws RemoteException {
                        callback.onPlaylistMetadataChanged(seq, newMetadata);
                    }
                });
            }
        }
        MediaItem oldCurrentItem = oldPlayer.getCurrentMediaItem();
        final MediaItem newCurrentItem = getCurrentMediaItemOrNull();
        if (!ObjectsCompat.equals(oldCurrentItem, newCurrentItem)) {
            dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onCurrentMediaItemChanged(seq, newCurrentItem,
                            getCurrentMediaItemIndex(), getPreviousMediaItemIndex(),
                            getNextMediaItemIndex());
                }
            });
        }
        final @SessionPlayer.RepeatMode int repeatMode = getRepeatMode();
        if (oldPlayer.getRepeatMode() != repeatMode) {
            dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onRepeatModeChanged(seq, repeatMode, getCurrentMediaItemIndex(),
                            getPreviousMediaItemIndex(), getNextMediaItemIndex());
                }
            });
        }
        final @SessionPlayer.ShuffleMode int shuffleMode = getShuffleMode();
        if (oldPlayer.getShuffleMode() != shuffleMode) {
            dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onShuffleModeChanged(seq, shuffleMode, getCurrentMediaItemIndex(),
                            getPreviousMediaItemIndex(), getNextMediaItemIndex());
                }
            });
        }

        // Always forcefully send the player state and buffered state to send the current position
        // and buffered position.
        final long currentTimeMs = SystemClock.elapsedRealtime();
        final long positionMs = getCurrentPosition();
        final int playerState = getPlayerState();
        dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
            @Override
            public void run(ControllerCb callback, int seq) throws RemoteException {
                callback.onPlayerStateChanged(seq, currentTimeMs, positionMs, playerState);
            }
        });
        final MediaItem item = getCurrentMediaItemOrNull();
        if (item != null) {
            final int bufferingState = getBufferingState();
            final long bufferedPositionMs = getBufferedPosition();
            dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onBufferingStateChanged(seq, item, bufferingState, bufferedPositionMs,
                            SystemClock.elapsedRealtime(), getCurrentPosition());
                }
            });
        }
        final float speed = getPlaybackSpeed();
        if (speed != oldPlayer.getPlaybackSpeed()) {
            dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onPlaybackSpeedChanged(seq, currentTimeMs, positionMs, speed);
                }
            });
        }
        // Note: AudioInfo is updated outside of this API.
    }

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    void notifyPlaybackInfoChangedNotLocked(final MediaController.PlaybackInfo info) {
        dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
            @Override
            public void run(ControllerCb callback, int seq) throws RemoteException {
                callback.onPlaybackInfoChanged(seq, info);
            }
        });
    }

    void dispatchRemoteControllerTaskWithoutReturn(@NonNull RemoteControllerTask task) {
        List<ControllerInfo> controllers =
                mSessionStub.getConnectedControllersManager().getConnectedControllers();
        controllers.add(mSessionLegacyStub.getControllersForAll());
        for (int i = 0; i < controllers.size(); i++) {
            ControllerInfo controller = controllers.get(i);
            dispatchRemoteControllerTaskWithoutReturn(controller, task);
        }
    }

    void dispatchRemoteControllerTaskWithoutReturn(@NonNull ControllerInfo controller,
            @NonNull RemoteControllerTask task) {
        if (!isConnected(controller)) {
            // Do not send command to an unconnected controller.
            return;
        }
        try {
            final int seq;
            final SequencedFutureManager manager =
                    mSessionStub.getConnectedControllersManager()
                            .getSequencedFutureManager(controller);
            if (manager != null) {
                seq = manager.obtainNextSequenceNumber();
            } else {
                // Can be null in two cases. Use the 0 as sequence number in both cases because
                //     Case 1) Controller is from the legacy stub
                //             -> Sequence number isn't needed, so 0 is OK
                //     Case 2) Controller is removed after the connection check above
                //             -> Call will fail below or ignored by the controller, so 0 is OK.
                seq = 0;
            }
            task.run(controller.getControllerCb(), seq);
        } catch (DeadObjectException e) {
            onDeadObjectException(controller, e);
        } catch (RemoteException e) {
            // Currently it's TransactionTooLargeException or DeadSystemException.
            // We'd better to leave log for those cases because
            //   - TransactionTooLargeException means that we may need to fix our code.
            //     (e.g. add pagination or special way to deliver Bitmap)
            //   - DeadSystemException means that errors around it can be ignored.
            Log.w(TAG, "Exception in " + controller.toString(), e);
        }
    }

    private ListenableFuture<SessionResult> dispatchRemoteControllerTask(
            @NonNull ControllerInfo controller, @NonNull RemoteControllerTask task) {
        if (!isConnected(controller)) {
            return SessionResult.createFutureWithResult(RESULT_ERROR_SESSION_DISCONNECTED);
        }
        try {
            final ListenableFuture<SessionResult> future;
            final int seq;
            final SequencedFutureManager manager =
                    mSessionStub.getConnectedControllersManager()
                            .getSequencedFutureManager(controller);
            if (manager != null) {
                future = manager.createSequencedFuture(RESULT_WHEN_CLOSED);
                seq = ((SequencedFuture<SessionResult>) future).getSequenceNumber();
            } else {
                // Can be null in two cases. Use the 0 as sequence number in both cases because
                //     Case 1) Controller is from the legacy stub
                //             -> Sequence number isn't needed, so 0 is OK
                //     Case 2) Controller is removed after the connection check above
                //             -> Call will fail below or ignored by the controller, so 0 is OK.
                seq = 0;
                future = SessionResult.createFutureWithResult(SessionResult.RESULT_SUCCESS);
            }
            task.run(controller.getControllerCb(), seq);
            return future;
        } catch (DeadObjectException e) {
            onDeadObjectException(controller, e);
            return SessionResult.createFutureWithResult(RESULT_ERROR_SESSION_DISCONNECTED);
        } catch (RemoteException e) {
            // Currently it's TransactionTooLargeException or DeadSystemException.
            // We'd better to leave log for those cases because
            //   - TransactionTooLargeException means that we may need to fix our code.
            //     (e.g. add pagination or special way to deliver Bitmap)
            //   - DeadSystemException means that errors around it can be ignored.
            Log.w(TAG, "Exception in " + controller.toString(), e);
        }
        return SessionResult.createFutureWithResult(RESULT_ERROR_UNKNOWN);
    }

    /**
     * Removes controller. Call this when DeadObjectException is happened with binder call.
     */
    private void onDeadObjectException(ControllerInfo controller, DeadObjectException e) {
        if (DEBUG) {
            Log.d(TAG, controller.toString() + " is gone", e);
        }
        // Note: Only removing from MediaSessionStub and ignoring (legacy) stubs would be fine for
        //       now. Because calls to the legacy stubs doesn't throw DeadObjectException.
        mSessionStub.getConnectedControllersManager().removeController(controller);
    }

    @Nullable
    private ComponentName getServiceComponentByAction(@NonNull String action) {
        PackageManager pm = mContext.getPackageManager();
        Intent queryIntent = new Intent(action);
        queryIntent.setPackage(mContext.getPackageName());
        List<ResolveInfo> resolveInfos = pm.queryIntentServices(queryIntent, 0 /* flags */);
        if (resolveInfos == null || resolveInfos.isEmpty()) {
            return null;
        }
        ResolveInfo resolveInfo = resolveInfos.get(0);
        return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
    }

    ///////////////////////////////////////////////////
    // Inner classes
    ///////////////////////////////////////////////////
    @FunctionalInterface
    interface PlayerTask<T> {
        T run(@NonNull SessionPlayer player) throws Exception;
    }

    @FunctionalInterface
    interface RemoteControllerTask {
        void run(ControllerCb controller, int seq) throws RemoteException;
    }

    private static class SessionPlayerCallback extends SessionPlayer.PlayerCallback implements
            MediaItem.OnMetadataChangedListener {
        private final WeakReference<MediaSessionImplBase> mSession;
        private MediaItem mMediaItem;
        private List<MediaItem> mList;
        private final PlaylistItemListener mPlaylistItemChangedListener;

        SessionPlayerCallback(MediaSessionImplBase session) {
            mSession = new WeakReference<>(session);
            mPlaylistItemChangedListener = new PlaylistItemListener(session);
        }

        @Override
        public void onCurrentMediaItemChanged(@NonNull final SessionPlayer player,
                @NonNull final MediaItem item) {
            final MediaSessionImplBase session = getSession();
            if (session == null || player == null || session.getPlayer() != player) {
                return;
            }
            synchronized (session.mLock) {
                if (mMediaItem != null) {
                    mMediaItem.removeOnMetadataChangedListener(this);
                }
                if (item != null) {
                    item.addOnMetadataChangedListener(session.mCallbackExecutor, this);
                }
                mMediaItem = item;
            }

            // Note: No sanity check whether the item is in the playlist.
            // It's possible that current media item isn't in the playlist, if it was removed while
            // playing.
            if (item != null) {
                updateCurrentMediaItemMetadataWithDuration(player);
            }
            notifyCurrentMediaItemChanged(item);
        }

        @Override
        public void onPlayerStateChanged(@NonNull final SessionPlayer player, final int state) {
            final MediaSessionImplBase session = getSession();
            if (session == null || player == null || session.getPlayer() != player) {
                return;
            }
            session.getCallback().onPlayerStateChanged(session.getInstance(), state);
            updateCurrentMediaItemMetadataWithDuration(player);
            session.dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onPlayerStateChanged(seq, SystemClock.elapsedRealtime(),
                            player.getCurrentPosition(), state);
                }
            });
        }

        @Override
        public void onBufferingStateChanged(@NonNull final SessionPlayer player,
                final MediaItem item, final int state) {
            updateCurrentMediaItemMetadataWithDuration(player);
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onBufferingStateChanged(seq, item, state, player.getBufferedPosition(),
                            SystemClock.elapsedRealtime(), player.getCurrentPosition());
                }
            });
        }

        @Override
        public void onPlaybackSpeedChanged(@NonNull final SessionPlayer player, final float speed) {
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onPlaybackSpeedChanged(seq, SystemClock.elapsedRealtime(),
                            player.getCurrentPosition(), speed);
                }
            });
        }

        @Override
        public void onSeekCompleted(@NonNull final SessionPlayer player, final long position) {
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onSeekCompleted(seq, SystemClock.elapsedRealtime(),
                            player.getCurrentPosition(), position);
                }
            });
        }

        @Override
        public void onPlaylistChanged(@NonNull final SessionPlayer player,
                final List<MediaItem> list, final MediaMetadata metadata) {
            final MediaSessionImplBase session = getSession();
            if (session == null || player == null || session.getPlayer() != player) {
                return;
            }
            synchronized (session.mLock) {
                if (mList != null) {
                    for (int i = 0; i < mList.size(); i++) {
                        mList.get(i).removeOnMetadataChangedListener(mPlaylistItemChangedListener);
                    }
                }
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        list.get(i).addOnMetadataChangedListener(session.mCallbackExecutor,
                                mPlaylistItemChangedListener);
                    }
                }
                mList = list;
            }

            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onPlaylistChanged(seq, list, metadata,
                            session.getCurrentMediaItemIndex(), session.getPreviousMediaItemIndex(),
                            session.getNextMediaItemIndex());
                }
            });
        }

        @Override
        public void onPlaylistMetadataChanged(@NonNull final SessionPlayer player,
                final MediaMetadata metadata) {
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onPlaylistMetadataChanged(seq, metadata);
                }
            });
        }

        @Override
        public void onRepeatModeChanged(@NonNull final SessionPlayer player, final int repeatMode) {
            final MediaSessionImplBase session = getSession();
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onRepeatModeChanged(seq, repeatMode,
                            session.getCurrentMediaItemIndex(),
                            session.getPreviousMediaItemIndex(),
                            session.getNextMediaItemIndex());
                }
            });
        }

        @Override
        public void onShuffleModeChanged(@NonNull final SessionPlayer player,
                final int shuffleMode) {
            final MediaSessionImplBase session = getSession();
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onShuffleModeChanged(seq, shuffleMode,
                            session.getCurrentMediaItemIndex(),
                            session.getPreviousMediaItemIndex(),
                            session.getNextMediaItemIndex());
                }
            });
        }

        @Override
        public void onPlaybackCompleted(@NonNull SessionPlayer player) {
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onPlaybackCompleted(seq);
                }
            });
        }

        @Override
        public void onAudioAttributesChanged(@NonNull final SessionPlayer player,
                final AudioAttributesCompat attributes) {
            final MediaSessionImplBase session = getSession();
            if (session == null || player == null || session.getPlayer() != player) {
                return;
            }
            MediaController.PlaybackInfo newInfo = session.createPlaybackInfo(player, attributes);
            MediaController.PlaybackInfo oldInfo;
            synchronized (session.mLock) {
                oldInfo = session.mPlaybackInfo;
                session.mPlaybackInfo = newInfo;
            }
            if (!ObjectsCompat.equals(newInfo, oldInfo)) {
                session.notifyPlaybackInfoChangedNotLocked(newInfo);
                if (!(player instanceof RemoteSessionPlayer)) {
                    int oldStreamType = getLegacyStreamType(
                            oldInfo == null ? null : oldInfo.getAudioAttributes());
                    int newStreamType = getLegacyStreamType(newInfo.getAudioAttributes());
                    if (oldStreamType != newStreamType) {
                        session.getSessionCompat().setPlaybackToLocal(newStreamType);
                    }
                }
            }
        }

        @Override
        public void onVideoSizeChanged(@NonNull SessionPlayer player, @NonNull VideoSize size) {
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onVideoSizeChanged(seq, MediaUtils.upcastForPreparceling(size));
                }
            });
        }

        @Override
        public void onTracksChanged(@NonNull SessionPlayer player,
                @NonNull List<TrackInfo> tracks) {
            final MediaSessionImplBase session = getSession();
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onTracksChanged(seq, MediaUtils.upcastForPreparceling(tracks),
                            MediaUtils.upcastForPreparceling(
                                    session.getSelectedTrack(TrackInfo.MEDIA_TRACK_TYPE_VIDEO)),
                            MediaUtils.upcastForPreparceling(
                                    session.getSelectedTrack(TrackInfo.MEDIA_TRACK_TYPE_AUDIO)),
                            MediaUtils.upcastForPreparceling(
                                    session.getSelectedTrack(TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE)),
                            MediaUtils.upcastForPreparceling(
                                    session.getSelectedTrack(TrackInfo.MEDIA_TRACK_TYPE_METADATA)));
                }
            });
        }

        @Override
        public void onTrackSelected(@NonNull SessionPlayer player,
                @NonNull final TrackInfo trackInfo) {
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onTrackSelected(seq, MediaUtils.upcastForPreparceling(trackInfo));
                }
            });
        }

        @Override
        public void onTrackDeselected(@NonNull SessionPlayer player,
                @NonNull final TrackInfo trackInfo) {
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onTrackDeselected(seq, MediaUtils.upcastForPreparceling(trackInfo));
                }
            });
        }

        @Override
        public void onSubtitleData(@NonNull final SessionPlayer player,
                @NonNull final MediaItem item, @NonNull final TrackInfo track,
                @NonNull final SubtitleData data) {
            dispatchRemoteControllerTask(player, new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onSubtitleData(seq, item, track, data);
                }
            });
        }

        // Called only when current media item's metadata is changed.
        @Override
        public void onMetadataChanged(@NonNull MediaItem item) {
            // Sanity check, just in case.
            final MediaSessionImplBase session = getSession();
            if (session == null) {
                return;
            }
            SessionPlayer player = session.getPlayer();
            if (player.getCurrentMediaItem() == item) {
                updateCurrentMediaItemMetadataWithDuration(player);
                notifyCurrentMediaItemChanged(item);
            }
        }

        private MediaSessionImplBase getSession() {
            final MediaSessionImplBase session = mSession.get();
            if (session == null && DEBUG) {
                Log.d(TAG, "Session is closed", new IllegalStateException());
            }
            return session;
        }

        private void dispatchRemoteControllerTask(@NonNull SessionPlayer player,
                @NonNull RemoteControllerTask task) {
            final MediaSessionImplBase session = getSession();
            if (session == null || player == null || session.getPlayer() != player) {
                return;
            }
            session.dispatchRemoteControllerTaskWithoutReturn(task);
        }

        /**
         * Update metadata of the player's current media item with duration.
         *
         * @param player player to get duration
         */
        private void updateCurrentMediaItemMetadataWithDuration(SessionPlayer player) {
            final long duration = player.getDuration();
            final MediaItem currentMediaItem = player.getCurrentMediaItem();
            // Check if the duration from the player can be the currentMediaItem's duration.
            if (currentMediaItem != null
                    && player.getPlayerState() != PLAYER_STATE_IDLE && duration > 0
                    && duration != UNKNOWN_TIME) {
                MediaMetadata metadataWithDurationUpdate = null;
                MediaMetadata currentMediaItemMetadata = currentMediaItem.getMetadata();
                if (currentMediaItemMetadata != null) {
                    if (!currentMediaItemMetadata.containsKey(METADATA_KEY_DURATION)) {
                        metadataWithDurationUpdate =
                                new MediaMetadata.Builder(currentMediaItemMetadata)
                                        .putLong(METADATA_KEY_DURATION, duration)
                                        .putLong(METADATA_KEY_PLAYABLE, 1)
                                        .build();
                    } else {
                        long durationFromMetadata =
                                currentMediaItemMetadata.getLong(METADATA_KEY_DURATION);
                        if (duration != durationFromMetadata) {
                            // Warns developers about the mismatch. Don't log media item here to
                            // keep metadata secure.
                            Log.w(TAG, "duration mismatch for an item."
                                    + " duration from player=" + duration
                                    + " duration from metadata=" + durationFromMetadata
                                    + ". May be a timing issue?");
                            // Trust duration in the metadata set by developer.
                            // In theory, duration may differ if the current item has been
                            // changed before the getDuration(). So it's better not touch
                            // duration set by developer.
                        }
                    }
                } else {
                    metadataWithDurationUpdate = new MediaMetadata.Builder()
                            .putLong(METADATA_KEY_DURATION, duration)
                            .putString(METADATA_KEY_MEDIA_ID, currentMediaItem.getMediaId())
                            .putLong(METADATA_KEY_PLAYABLE, 1)
                            .build();
                }
                if (metadataWithDurationUpdate != null) {
                    // Note: Don't do sanity check whether the currentMediaItemMetadata is still the
                    // currentMediaItem's metadata. Do best effort for not missing any notification
                    // changes.
                    // Note that updated metadata will be notified anyway via later
                    // SessionPlayerCallback#onMetadataChanged().
                    currentMediaItem.setMetadata(metadataWithDurationUpdate);
                }
            }
        }

        private void notifyCurrentMediaItemChanged(@Nullable MediaItem currentMediaItem) {
            final MediaSessionImplBase session = getSession();
            if (session == null) {
                return;
            }
            dispatchRemoteControllerTask(session.getPlayer(), new RemoteControllerTask() {
                @Override
                public void run(ControllerCb callback, int seq) throws RemoteException {
                    callback.onCurrentMediaItemChanged(seq, currentMediaItem,
                            session.getCurrentMediaItemIndex(),
                            session.getPreviousMediaItemIndex(),
                            session.getNextMediaItemIndex());
                }
            });
        }
    }

    static class PlaylistItemListener implements MediaItem.OnMetadataChangedListener {
        private final WeakReference<MediaSessionImplBase> mSession;

        PlaylistItemListener(MediaSessionImplBase session) {
            mSession = new WeakReference<>(session);
        }

        @Override
        public void onMetadataChanged(@NonNull final MediaItem item) {
            final MediaSessionImplBase session = mSession.get();
            if (session == null || item == null) {
                return;
            }
            final List<MediaItem> list = session.getPlaylist();
            if (list == null) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                if (item.equals(list.get(i))) {
                    session.dispatchRemoteControllerTaskWithoutReturn(new RemoteControllerTask() {
                        @Override
                        public void run(ControllerCb callback, int seq) throws RemoteException {
                            callback.onPlaylistChanged(seq, list,
                                    session.getPlaylistMetadata(),
                                    session.getCurrentMediaItemIndex(),
                                    session.getPreviousMediaItemIndex(),
                                    session.getNextMediaItemIndex());
                        }
                    });
                    return;
                }
            }
        }
    }

    static final class CombinedCommandResultFuture<T extends BaseResult>
            extends AbstractResolvableFuture<T> {
        final ListenableFuture<T>[] mFutures;
        AtomicInteger mSuccessCount = new AtomicInteger(0);

        @SafeVarargs
        public static <U extends BaseResult> CombinedCommandResultFuture<U> create(
                Executor executor, ListenableFuture<U>... futures) {
            return new CombinedCommandResultFuture<>(executor, futures);
        }

        private CombinedCommandResultFuture(Executor executor,
                ListenableFuture<T>[] futures) {
            mFutures = futures;
            for (int i = 0; i < mFutures.length; ++i) {
                final int cur = i;
                mFutures[i].addListener(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            T result = mFutures[cur].get();
                            int resultCode = result.getResultCode();
                            if (resultCode != SessionResult.RESULT_SUCCESS
                                    && resultCode != RESULT_INFO_SKIPPED) {
                                for (int j = 0; j < mFutures.length; ++j) {
                                    if (!mFutures[j].isCancelled() && !mFutures[j].isDone()
                                            && cur != j) {
                                        mFutures[j].cancel(true);
                                    }
                                }
                                set(result);
                            } else {
                                int cnt = mSuccessCount.incrementAndGet();
                                if (cnt == mFutures.length) {
                                    set(result);
                                }
                            }
                        } catch (Exception e) {
                            for (int j = 0; j < mFutures.length; ++j) {
                                if (!mFutures[j].isCancelled() && !mFutures[j].isDone()
                                        && cur != j) {
                                    mFutures[j].cancel(true);
                                }
                            }
                            setException(e);
                        }
                    }
                }, executor);
            }
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final class MediaButtonReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
                return;
            }
            Uri sessionUri = intent.getData();
            if (!ObjectsCompat.equals(sessionUri, mSessionUri)) {
                return;
            }
            KeyEvent keyEvent = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (keyEvent == null) {
                return;
            }
            getSessionCompat().getController().dispatchMediaButtonEvent(keyEvent);
        }
    };
}

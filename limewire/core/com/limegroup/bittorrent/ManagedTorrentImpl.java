package com.limegroup.bittorrent;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.limewire.concurrent.ExecutorsHelper;
import org.limewire.concurrent.SyncWrapper;
import org.limewire.core.settings.BittorrentSettings;
import org.limewire.core.settings.SharingSettings;
import org.limewire.http.reactor.LimeConnectingIOReactorFactory;
import org.limewire.io.Address;
import org.limewire.io.DiskException;
import org.limewire.io.NetworkInstanceUtils;
import org.limewire.net.address.StrictIpPortSet;
import org.limewire.service.ErrorService;
import org.limewire.swarm.SwarmSourceType;
import org.limewire.swarm.Swarmer;
import org.limewire.swarm.http.SwarmHttpSource;
import org.limewire.swarm.http.SwarmHttpSourceDownloader;
import org.limewire.swarm.impl.SwarmerImpl;
import org.limewire.util.FileUtils;

import com.limegroup.bittorrent.choking.Choker;
import com.limegroup.bittorrent.choking.ChokerFactory;
import com.limegroup.bittorrent.disk.DiskManagerListener;
import com.limegroup.bittorrent.disk.TorrentDiskManager;
import com.limegroup.bittorrent.handshaking.BTConnectionFetcher;
import com.limegroup.bittorrent.handshaking.BTConnectionFetcherFactory;
import com.limegroup.bittorrent.handshaking.piecestrategy.LargestGapStartPieceStrategy;
import com.limegroup.bittorrent.messages.BTHave;
import com.limegroup.bittorrent.swarm.BTSwarmCoordinator;
import com.limegroup.bittorrent.swarm.BTSwarmHttpSource;
import com.limegroup.bittorrent.tracking.TrackerManager;
import com.limegroup.bittorrent.tracking.TrackerManagerFactory;
import com.limegroup.gnutella.NetworkManager;
import com.limegroup.gnutella.URN;
import com.limegroup.gnutella.auth.ContentManager;
import com.limegroup.gnutella.auth.ContentResponseData;
import com.limegroup.gnutella.auth.ContentResponseObserver;
import com.limegroup.gnutella.filters.IPFilter;
import com.limegroup.gnutella.library.FileManager;
import com.limegroup.gnutella.util.EventDispatcher;
import com.limegroup.gnutella.util.LimeWireUtils;

/**
 * Class that keeps track of state relevant to a single torrent.
 * <p>
 * It manages various components relevant to the torrent download such as the
 * Choker, Connection Fetcher, Verifying Folder.
 * <p>
 * It keeps track of the known and connected peers and contains the logic for
 * starting and stopping the torrent.
 */
public class ManagedTorrentImpl implements ManagedTorrent, DiskManagerListener {

    private static final Log LOG = LogFactory.getLog(ManagedTorrentImpl.class);

    /**
     * A shared processing queue for disk-related tasks.
     */
    private static final ExecutorService DEFAULT_DISK_INVOKER = ExecutorsHelper
            .newProcessingQueue("ManagedTorrent");

    /** the executor of tasks involving network io. */
    private final ScheduledExecutorService networkInvoker;

    /**
     * Executor that changes the state of this torrent and does the moving of
     * files to the complete location, and other tasks involving disk io.
     */
    private ExecutorService diskInvoker = DEFAULT_DISK_INVOKER;

    /**
     * The list of known good TorrentLocations that we are not connected or
     * connecting to at the moment
     */
    private Set<TorrentLocation> _peers;

    /**
     * the meta info for this torrent
     */
    private BTMetaInfo _info;

    /**
     * The manager of disk operations.
     */
    private volatile TorrentDiskManager _folder;

    /**
     * The manager of tracker requests.
     */
    private final TrackerManager trackerManager;

    /**
     * Factory for our connection fetcher
     */
    private final BTConnectionFetcherFactory connectionFetcherFactory;

    /**
     * The fetcher of connections.
     */
    private volatile BTConnectionFetcher _connectionFetcher;

    /** Manager of the BT links of this torrent */
    private final BTLinkManager linkManager;

    /** Factory for our chokers */
    private final ChokerFactory chokerFactory;

    /**
     * The manager of choking logic
     */
    private Choker choker;

    private final Swarmer webSeedSwarmer;

    // TODO instead of webseeding variable update swarm isActive to return true,
    // only if there are active connections
    private final AtomicBoolean webseeding = new AtomicBoolean(false);

    /**
     * Locking this->state.getLock() ok.
     */
    private final SyncWrapper<TorrentState> state = new SyncWrapper<TorrentState>(
            TorrentState.QUEUED);

    /** The downloaded data this session */
    private volatile long totalDown;

    /** Event dispatcher for events generated by this torrent */
    private final EventDispatcher<TorrentEvent, TorrentEventListener> dispatcher;

    private final TorrentContext context;

    private final NetworkManager networkManager;

    private final ContentManager contentManager;

    private final IPFilter ipFilter;

    private final TorrentManager torrentManager;

    private final FileManager fileManager;

    private final NetworkInstanceUtils networkInstanceUtils;

    private AtomicBoolean firstChunkVerifiedEventDispatched;
    
    /**
     * Constructs new ManagedTorrent
     * 
     * @param dispatcher a dispatcher for events generated by this torrent
     * @param networkInvoker a <tt>SchedulingThreadPool</tt> to execute network
     *        tasks on
     */
    ManagedTorrentImpl(TorrentContext context,
            EventDispatcher<TorrentEvent, TorrentEventListener> dispatcher,
            ScheduledExecutorService networkInvoker, NetworkManager networkManager,
            TrackerManagerFactory trackerManagerFactory, ChokerFactory chokerFactory,
            BTLinkManagerFactory linkManagerFactory,
            BTConnectionFetcherFactory connectionFetcherFactory, ContentManager contentManager,
            IPFilter ipFilter, TorrentManager torrentManager, FileManager fileManager,
            NetworkInstanceUtils networkInstanceUtils, LimeConnectingIOReactorFactory limeConnectingIOReactorFactory) {
        this.context = context;
        this.networkInvoker = networkInvoker;
        this.dispatcher = dispatcher;
        this.networkManager = networkManager;
        this.chokerFactory = chokerFactory;
        this.connectionFetcherFactory = connectionFetcherFactory;
        this.contentManager = contentManager;
        this.ipFilter = ipFilter;
        this.torrentManager = torrentManager;
        this.fileManager = fileManager;
        this.networkInstanceUtils = networkInstanceUtils;
        _info = context.getMetaInfo();
        _folder = getContext().getDiskManager();
        _peers = Collections.emptySet();
        linkManager = linkManagerFactory.getLinkManager();
        trackerManager = trackerManagerFactory.getTrackerManager(this);
        this.firstChunkVerifiedEventDispatched = new AtomicBoolean(false);

        BTMetaInfo metaInfo = context.getMetaInfo();
        TorrentFileSystem torrentFileSystem = context.getFileSystem();
        TorrentDiskManager torrentDiskManager = context.getDiskManager();
        BTSwarmCoordinator btCoordinator = new BTSwarmCoordinator(metaInfo, torrentFileSystem,
                torrentDiskManager, new LargestGapStartPieceStrategy(metaInfo));

        webSeedSwarmer = new SwarmerImpl(btCoordinator);
        webSeedSwarmer.register(SwarmSourceType.HTTP, new SwarmHttpSourceDownloader(limeConnectingIOReactorFactory, btCoordinator, LimeWireUtils.getHttpServer()));
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#setScraping()
     */
    public void setScraping() {
        synchronized (state.getLock()) {
            if (state.get() == TorrentState.WAITING_FOR_TRACKER)
                state.set(TorrentState.SCRAPING);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getInfoHash()
     */
    public byte[] getInfoHash() {
        return _info.getInfoHash();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getMetaInfo()
     */
    public BTMetaInfo getMetaInfo() {
        return _info;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getContext()
     */
    public TorrentContext getContext() {
        return context;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#isComplete()
     */
    public boolean isComplete() {
        return state.get() != TorrentState.DISK_PROBLEM && _folder.isComplete();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#start()
     */
    public void start() {
        if (LOG.isDebugEnabled())
            LOG.debug("requesting torrent start", new Exception());

        synchronized (state.getLock()) {
            if (state.get() != TorrentState.QUEUED)
                throw new IllegalStateException("torrent should be queued but is " + state.get());
        }
        dispatchEvent(TorrentEvent.Type.STARTING);

        diskInvoker.execute(new Runnable() {
            public void run() {
                if (state.get() != TorrentState.QUEUED) // something happened,
                    // do not start.
                    return;

                LOG.debug("executing torrent start");

                initializeTorrent();
                initializeFolder();
                if (state.get() == TorrentState.DISK_PROBLEM)
                    return;

                dispatchEvent(TorrentEvent.Type.STARTED);

                TorrentState s = state.get();
                if (s == TorrentState.SEEDING || s == TorrentState.VERIFYING)
                    return;

                validateTorrent();
                startConnecting();
            }

        });
    }

    private void webseed() {
        // TODO use combination of BTSwarmSource isfinished method refactor
        // BTConnectionfetcher to
        // to iteratively reconnect to the SwarmSource, instead of keeping a
        // connection until the download is finished.
        // TODO also need to know when the webseeding stops and reset the
        // state/webseeding variables
        BTMetaInfo metaInfo = context.getMetaInfo();
        if (metaInfo.hasWebSeeds()) {
            TorrentFileSystem torrentFileSystem = context.getFileSystem();

            long completeSize = torrentFileSystem.getTotalSize();
            for (URI uri : metaInfo.getWebSeeds()) {
                if(SwarmHttpSource.isValidSource(uri)) {
                    webSeedSwarmer.addSource(new BTSwarmHttpSource(uri, completeSize));
                    webseeding.set(true);
                }
            }
        }
    }

    private void validateTorrent() {
        ContentResponseObserver observer = new ContentResponseObserver() {
            public void handleResponse(URN urn, ContentResponseData response) {
                if (response != null && !response.isOK()
                        && urn.equals(context.getMetaInfo().getURN())) {

                    boolean wasActive;
                    synchronized (ManagedTorrentImpl.this) {
                        synchronized (state.getLock()) {
                            wasActive = isActive();
                            state.set(TorrentState.INVALID);
                        }

                        if (wasActive)
                            stopImpl();
                    }
                }
            }
        };
        contentManager.request(context.getMetaInfo().getURN(), observer, 5000);
    }

    /**
     * Starts the tracker request if necessary and the fetching of connections.
     */
    private void startConnecting() {
        boolean shouldFetch = false;
        synchronized (state.getLock()) {
            if (state.get() != TorrentState.QUEUED)
                return;

            // kick off connectors if we already have some addresses
            if (_peers.size() > 0) {
                state.set(TorrentState.CONNECTING);
                shouldFetch = true;
            } else
                state.set(TorrentState.SCRAPING);
        }

        if (shouldFetch)
            _connectionFetcher.fetch();

        // starting webseed downloads
        webSeedSwarmer.start();
        webseed();

        // connect to tracker(s)
        trackerManager.announceStart();

        // start the choking / unchoking of connections
        choker.start();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#stop()
     */
    public synchronized void stop() {

        if (!isActive()) {
            throw new IllegalStateException("torrent cannot be stopped in state " + state.get());
        }

        state.set(TorrentState.STOPPED);

        stopImpl();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.limegroup.bittorrent.ManagedTorrent#diskExceptionHappened(org.limewire
     * .io.DiskException)
     */
    public synchronized void diskExceptionHappened(DiskException e) {
        synchronized (state.getLock()) {
            if (state.get() == TorrentState.DISK_PROBLEM)
                return;
            state.set(TorrentState.DISK_PROBLEM);
        }
        stopImpl();
        if (BittorrentSettings.REPORT_DISK_PROBLEMS.getBoolean())
            ErrorService.error(e);
    }

    /**
     * Performs the actual stop.
     */
    private synchronized void stopImpl() {

        if (!stopState())
            throw new IllegalStateException("stopping in wrong state " + state.get());

        // close the folder and stop the periodic tasks
        _folder.close();

        // fire off an announcement to the tracker
        // unless we're stopping because of a tracker failure
        String description = null;
        if (state.get() != TorrentState.TRACKER_FAILURE)
            trackerManager.announceStop();
        else
            description = trackerManager.getLastFailureReason();

        // write the snapshot if not complete
        if (!_folder.isComplete()) {
            Runnable saver = new Runnable() {
                public void run() {
                    try {
                        saveInfoMapInIncomplete();
                    } catch (IOException ignored) {
                    }
                }
            };
            diskInvoker.execute(saver);
        }

        // shutdown various components
        Runnable closer = new Runnable() {
            public void run() {
                choker.shutdown();
                linkManager.shutdown();
                _connectionFetcher.shutdown();
                webSeedSwarmer.shutdown();
                webseeding.set(false);
            }
        };
        networkInvoker.execute(closer);

        dispatchEvent(TorrentEvent.Type.STOPPED, description);

        LOG.debug("Torrent stopped!");
    }

    private void saveInfoMapInIncomplete() throws IOException {
        File file = new File(context.getFileSystem().getIncompleteFile().getParent(), context.getFileSystem().getName() + ".dat");
        FileUtils.writeObject(file, context.getMetaInfo().toMemento());
    }

    private void dispatchEvent(TorrentEvent.Type type, String description) {
        TorrentEvent evt = new TorrentEvent(this, type, this, description);
        dispatcher.dispatchEvent(evt);
    }

    private void dispatchEvent(TorrentEvent.Type type) {
        dispatchEvent(type, null);
    }

    /**
     * @return if the current state is a stopped state.
     */
    private boolean stopState() {
        switch (state.get()) {
        case PAUSED:
        case STOPPED:
        case DISK_PROBLEM:
        case TRACKER_FAILURE:
        case INVALID:
            return true;
        }
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#pause()
     */
    public synchronized void pause() {
        boolean wasActive = false;
        synchronized (state.getLock()) {
            if (!isActive() && state.get() != TorrentState.QUEUED)
                return;

            wasActive = isActive();
            state.set(TorrentState.PAUSED);
        }
        if (wasActive)
            stopImpl();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#resume()
     */
    public boolean resume() {
        synchronized (state.getLock()) {
            switch (state.get()) {
            case PAUSED:
            case TRACKER_FAILURE:
            case STOPPED:
                state.set(TorrentState.QUEUED);
                return true;
            default:
                return false;
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.limegroup.bittorrent.ManagedTorrent#linkClosed(com.limegroup.bittorrent
     * .BTLink)
     */
    public void linkClosed(BTLink btc) {
        if (btc.isWorthRetrying()) {
            // this forgets any strikes on the location
            TorrentLocation ep = new TorrentLocation(btc.getEndpoint());
            ep.strike();
            _peers.add(ep);
        }
        removeConnection(btc);

        if (!needsMoreConnections())
            return;

        TorrentState s = state.get();
        if (s == TorrentState.DOWNLOADING || s == TorrentState.CONNECTING)
            _connectionFetcher.fetch();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.limegroup.bittorrent.ManagedTorrent#linkInterested(com.limegroup.
     * bittorrent.BTLink)
     */
    public void linkInterested(BTLink interested) {
        if (!interested.isChoked())
            rechoke();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.limegroup.bittorrent.ManagedTorrent#linkNotInterested(com.limegroup
     * .bittorrent.BTLink)
     */
    public void linkNotInterested(BTLink notInterested) {
        if (!notInterested.isChoked())
            rechoke();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#trackerRequestFailed()
     */
    public void trackerRequestFailed() {
        synchronized (state.getLock()) {
            if (state.get() == TorrentState.SCRAPING)
                state.set(TorrentState.WAITING_FOR_TRACKER);
        }
        if (BittorrentSettings.TORRENT_ALTLOC_SEARCH.getValue()) {
            dispatchEvent(TorrentEvent.Type.TRACKER_FAILED);
        }
    }

    /**
     * Initializes some state relevant to the torrent
     */
    private void initializeTorrent() {
        _peers = Collections.synchronizedSet(new StrictIpPortSet<TorrentLocation>());
        choker = chokerFactory.getChoker(linkManager, false);
        _connectionFetcher = connectionFetcherFactory.getBTConnectionFetcher(this);
    }

    /**
     * Initializes the verifying folder
     */
    private void initializeFolder() {
        try {
            _folder.open(this);
            saveInfoMapInIncomplete();

        } catch (IOException ioe) {
            // problem opening files cannot recover.
            if (LOG.isDebugEnabled())
                LOG.debug("unrecoverable error", ioe);

            state.set(TorrentState.DISK_PROBLEM);
            return;
        }

        // if we happen to have the complete torrent in the incomplete folder
        // move it to the complete folder.
        if (_folder.isComplete())
            completeTorrentDownload();
        else if (_folder.isVerifying())
            state.set(TorrentState.VERIFYING);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#verificationComplete()
     */
    public void verificationComplete() {
        diskInvoker.execute(new Runnable() {
            public void run() {
                synchronized (state.getLock()) {
                    if (state.get() != TorrentState.VERIFYING)
                        return;
                    state.set(TorrentState.QUEUED);
                }
                startConnecting();
                if (_folder.isComplete())
                    completeTorrentDownload();
            }
        });
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#chunkVerified(int)
     */
    public void chunkVerified(int in) {
        if (firstChunkVerifiedEventDispatched.compareAndSet(false, true) && !_info.isPrivate()
                && SharingSettings.SHARE_TORRENT_META_FILES.getValue()
                && BittorrentSettings.TORRENT_AUTO_PUBLISH.getValue()) {
            // add yourself to the DHT as someone sharing this torrent
            dispatchEvent(TorrentEvent.Type.FIRST_CHUNK_VERIFIED);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("got completed chunk " + in);

        if (_folder.isVerifying())
            return;

        final BTHave have = new BTHave(in);
        Runnable haveNotifier = new Runnable() {
            public void run() {
                linkManager.sendHave(have);
            }
        };
        networkInvoker.execute(haveNotifier);

        if (_folder.isComplete()) {
            LOG.info("file is complete");
            diskInvoker.execute(new Runnable() {
                public void run() {
                    if (isDownloading())
                        completeTorrentDownload();
                }
            });
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getState()
     */
    public TorrentState getState() {
        return state.get();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.limegroup.bittorrent.ManagedTorrent#addEndpoint(com.limegroup.bittorrent
     * .TorrentLocation)
     */
    public void addEndpoint(TorrentLocation to) {
        if (_peers.contains(to) || linkManager.isConnectedTo(to))
            return;
        if (!ipFilter.allow(to.getAddress()))
            return;
        if (networkInstanceUtils.isMe(to.getAddress(), to.getPort())) {
            return;
        }
        if (_peers.add(to)) {
            synchronized (state.getLock()) {
                if (state.get() == TorrentState.SCRAPING)
                    state.set(TorrentState.CONNECTING);
            }
            _connectionFetcher.fetch();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#stopVoluntarily()
     */
    public synchronized void stopVoluntarily() {
        boolean stop = false;
        synchronized (state.getLock()) {
            if (!isActive())
                return;
            if (state.get() != TorrentState.SEEDING) {
                state.set(TorrentState.TRACKER_FAILURE);
                stop = true;
            }
        }
        if (stop)
            stopImpl();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#needsMoreConnections()
     */
    public boolean needsMoreConnections() {
        if (!isActive())
            return false;

        // if we are complete, do not open any sockets - the active torrents
        // will need them.
        if (isComplete() && torrentManager.hasNonSeeding())
            return false;

        // provision some slots for incoming connections unless we're firewalled
        // https://hal.inria.fr/inria-00162088/en/ recommends 1/2, we'll do 3/5
        int limit = torrentManager.getMaxTorrentConnections();
        if (networkManager.acceptedIncomingConnection())
            limit = limit * 3 / 5;
        return linkManager.getNumConnections() < limit;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.limegroup.bittorrent.ManagedTorrent#shouldAddConnection(com.limegroup
     * .bittorrent.TorrentLocation)
     */
    public boolean shouldAddConnection(TorrentLocation loc) {
        if (linkManager.isConnectedTo(loc))
            return false;
        return linkManager.getNumConnections() < torrentManager.getMaxTorrentConnections();
    }

    /*
     * (non-Javadoc)
     * 
     * @seecom.limegroup.bittorrent.ManagedTorrent#addConnection(com.limegroup.
     * bittorrent.BTLink)
     */
    public boolean addConnection(final BTConnection btc) {
        if (LOG.isDebugEnabled())
            LOG.debug("trying to add connection " + btc.toString());

        boolean shouldAdd = false;
        synchronized (state.getLock()) {
            switch (state.get()) {
            case CONNECTING:
            case SCRAPING:
            case WAITING_FOR_TRACKER:
                state.set(TorrentState.DOWNLOADING);
                dispatchEvent(TorrentEvent.Type.DOWNLOADING);
            case DOWNLOADING:
            case SEEDING:
                shouldAdd = true;
            }
        }

        if (!shouldAdd)
            return false;

        linkManager.addConnection(btc);
        _peers.remove(btc.getEndpoint());
        if (LOG.isDebugEnabled())
            LOG.debug("added connection " + btc.toString());
        return true;
    }

    /**
     * private helper method, removing connection
     */
    private void removeConnection(final BTLink btc) {
        if (LOG.isDebugEnabled())
            LOG.debug("removing connection " + btc.toString());
        linkManager.removeLink(btc);
        if (btc.isUploading())
            rechoke();
        boolean connectionsEmpty = linkManager.getNumConnections() == 0;
        boolean peersEmpty = _peers.isEmpty();
        synchronized (state.getLock()) {
            if (connectionsEmpty && state.get() == TorrentState.DOWNLOADING) {
                if (peersEmpty)
                    state.set(TorrentState.WAITING_FOR_TRACKER);
                else
                    state.set(TorrentState.CONNECTING);
            }
        }
    }

    /**
     * saves the complete files to the shared folder
     */
    private synchronized void completeTorrentDownload() {

        // cancel all requests and uploads and disconnect from seeds
        Runnable r = new Runnable() {
            public void run() {
                linkManager.disconnectSeedsChokeRest();

                // clear the state as we no longer need it
                // (until source exchange is implemented)
                _peers.clear();
            }
        };
        networkInvoker.execute(r);

        // save the files to the destination folder
        try {
            saveFiles();
        } catch (IOException failed) {
            diskExceptionHappened(new DiskException(failed));
            return;
        }

        state.set(TorrentState.SEEDING);

        // switch the choker logic and resume uploads
        choker.shutdown();
        choker = chokerFactory.getChoker(linkManager, true);
        choker.start();
        choker.rechoke();

        webSeedSwarmer.shutdown();
        webseeding.set(false);
        // tell the tracker we are a seed now
        trackerManager.announceComplete();

        dispatchEvent(TorrentEvent.Type.COMPLETE);
    }

    /**
     * Saves the complete files to destination folder.
     */
    private void saveFiles() throws IOException {

        // close the folder
        synchronized (_folder) {
            if (!_folder.isOpen())
                return;

            _folder.close();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("folder closed");

        // move it to the complete location
        state.set(TorrentState.SAVING);
        context.getFileSystem().moveToCompleteFolder();
        if (!_info.isPrivate())
            addToLibrary();
        LOG.trace("saved files");
        context.initializeDiskManager(true);
        LOG.trace("initialized folder");

        // and re-open it for seeding.
        _folder = context.getDiskManager();
        if (LOG.isDebugEnabled())
            LOG.debug("new veryfing folder");

        _folder.open(this);
        if (LOG.isDebugEnabled())
            LOG.debug("folder opened");
    }

    private void addToLibrary() {
        boolean force = SharingSettings.SHARE_DOWNLOADED_FILES_IN_NON_SHARED_DIRECTORIES.getValue();
        File _completeFile = context.getFileSystem().getCompleteFile();
        if (_completeFile.isFile()) {
            if (force)
                fileManager.getGnutellaFileList().add(_completeFile);
            else
                fileManager.getManagedFileList().add(_completeFile);
        } else if (_completeFile.isDirectory()) {
            if(force) {
                fileManager.getGnutellaFileList().addFolder(_completeFile);
            } else {
                fileManager.getManagedFileList().addFolder(_completeFile);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getNextTrackerRequestTime()
     */
    public long getNextTrackerRequestTime() {
        return trackerManager.getNextTrackerRequestTime();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getTorrentLocation()
     */
    public TorrentLocation getTorrentLocation() {
        long now = System.currentTimeMillis();
        TorrentLocation ret = null;
        synchronized (_peers) {
            for (Iterator<TorrentLocation> iter = _peers.iterator(); iter.hasNext();) {
                TorrentLocation loc = iter.next();
                if (loc.isBusy(now))
                    continue;
                iter.remove();
                if (!linkManager.isConnectedTo(loc)) {
                    ret = loc;
                    break;
                }
            }
        }
        return ret;
    }

    /**
     * trigger a rechoking of the connections
     */
    private void rechoke() {
        choker.rechoke();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#isPaused()
     */
    public boolean isPaused() {
        return state.get() == TorrentState.PAUSED;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ManagedTorrentImpl))
            return false;
        ManagedTorrent mt = (ManagedTorrent) o;

        return Arrays.equals(mt.getInfoHash(), getInfoHash());
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#isActive()
     */
    public boolean isActive() {
        synchronized (state.getLock()) {
            if (isDownloading())
                return true;
            switch (state.get()) {
            case SEEDING:
            case VERIFYING:
            case SAVING:
                return true;
            }
        }
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#isPausable()
     */
    public boolean isPausable() {
        synchronized (state.getLock()) {
            if (isDownloading())
                return true;
            switch (state.get()) {
            case QUEUED:
            case VERIFYING:
                return true;
            }
        }
        return false;
    }

    /**
     * @return if the torrent is currently in one of the downloading states.
     */
    public boolean isDownloading() {
        //TODO refactor state logic to be a little more dyanmic
        switch (state.get()) {
        case WAITING_FOR_TRACKER:
        case SCRAPING:
        case CONNECTING:
        case DOWNLOADING:
            return true;
        }
        
        if(webseeding.get()) {
            return true;
        }
        
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getNumConnections()
     */
    public int getNumConnections() {
        return linkManager.getNumConnections();
    }
    
    @Override
    public List<Address> getSourceAddresses() {
        return linkManager.getSourceAddresses();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getNumPeers()
     */
    public int getNumPeers() {
        return _peers.size();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getNumNonInterestingPeers()
     */
    public int getNumNonInterestingPeers() {
        return linkManager.getNumNonInterestingPeers();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getNumChockingPeers()
     */
    public int getNumChockingPeers() {
        return linkManager.getNumChockingPeers();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#countDownloaded(int)
     */
    public void countDownloaded(int amount) {
        totalDown += amount;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getTotalUploaded()
     */
    public long getTotalUploaded() {
        return _info.getAmountUploaded();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getTotalDownloaded()
     */
    public long getTotalDownloaded() {
        return totalDown;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getRatio()
     */
    public float getRatio() {
        return _info.getRatio();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getAmountLost()
     */
    public long getAmountLost() {
        return _folder.getNumCorruptedBytes();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#hasNonBusyLocations()
     */
    public boolean hasNonBusyLocations() {
        long now = System.currentTimeMillis();
        synchronized (_peers) {
            for (TorrentLocation to : _peers) {
                if (!to.isBusy(now))
                    return true;
            }
        }
        return false;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getNextLocationRetryTime()
     */
    public long getNextLocationRetryTime() {
        long soonest = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        synchronized (_peers) {
            for (TorrentLocation to : _peers) {
                soonest = Math.min(soonest, to.getWaitTime(now));
                if (soonest == 0)
                    break;
            }
        }
        return soonest;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#shouldStop()
     */
    public boolean shouldStop() {
        return linkManager.getNumConnections() == 0 && _peers.size() == 0
                && state.get() != TorrentState.SEEDING;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getFetcher()
     */
    public BTConnectionFetcher getFetcher() {
        return _connectionFetcher;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.limegroup.bittorrent.ManagedTorrent#getNetworkScheduledExecutorService
     * ()
     */
    public ScheduledExecutorService getNetworkScheduledExecutorService() {
        return networkInvoker;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#measureBandwidth()
     */
    public void measureBandwidth() {
        linkManager.measureBandwidth();
    }


    /*
     * (non-Javadoc)
     * 
     * @see
     * com.limegroup.bittorrent.ManagedTorrent#getMeasuredBandwidth(boolean)
     */
    public float getMeasuredBandwidth(boolean downstream) {
        float bandwidth = linkManager.getMeasuredBandwidth(downstream);
        bandwidth += webSeedSwarmer.getMeasuredBandwidth(downstream);
        return bandwidth;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#getTriedHostCount()
     */
    public int getTriedHostCount() {
        return _connectionFetcher.getTriedHostCount();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#isUploading()
     */
    public boolean isUploading() {
        return linkManager.hasUploading();
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.bittorrent.ManagedTorrent#isSuspended()
     */
    public boolean isSuspended() {
        return isComplete() && linkManager.hasInterested() && !linkManager.hasUnchoked();
    }

    /*
     * (non-Javadoc)
     * @see com.limegroup.bittorrent.ManagedTorrent#getLinkManager()
     */
    public BTLinkManager getLinkManager() {
        return linkManager;
    }

    /*
     * (non-Javadoc)
     * @see com.limegroup.bittorrent.ManagedTorrent#getSwarmer()
     */
    public Swarmer getSwarmer() {
        return webSeedSwarmer;
    }

    @Override
    public int getNumUploadPeers() {
        return linkManager.getNumUploadingPeers();
    }
    
}

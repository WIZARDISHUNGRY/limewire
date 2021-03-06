package com.limegroup.gnutella;

import java.io.File;
import java.util.Set;

import org.limewire.core.api.download.DownloadAction;
import org.limewire.core.api.download.SaveLocationException;
import org.limewire.i18n.I18nMarker;
import org.limewire.io.GUID;
import org.limewire.io.IpPort;

import com.limegroup.bittorrent.ManagedTorrent;
import com.limegroup.gnutella.browser.MagnetOptions;
import com.limegroup.gnutella.messages.QueryReply;

/**
 *  Defines the interface of a callback to notify about asynchronous backend 
 *  events. The methods in this interface fall into the following categories:
 *
 *  <ul>
 *  <li>Query replies (for displaying results) and query strings 
 *     (for the monitor)</li>
 *  <li>Update in shared file statistics</li>
 *  <li>Change of connection state</li>
 *  <li>New or dead uploads or downloads</li>
 *  <li>New chat requests and chat messages</li>
 *  <li>Error messages</li>
 *  </ul>
 */
public interface ActivityCallback extends DownloadCallback
{
    
    /**
     * Notifies the UI that a new query result has come in to the backend.
     * 
     * @param rfd the descriptor for the remote file
     * @param queryReply
     * @param locs the <tt>Set</tt> of alternate locations for the file
     */
	public void handleQueryResult(RemoteFileDesc rfd, QueryReply queryReply, Set<? extends IpPort> locs);

    /**
     * Add a query string to the monitor screen
     */
    public void handleQueryString( String query );

    /** Add an uploader to the upload window */
    public void addUpload(Uploader u);

    /** Remove an uploader from the upload window. */
    public void removeUpload(Uploader u);    

    public void handleSharedFileUpdate(File file);
    
    /** 
     * Notifies that all active uploads have been completed.
     */  
    public void uploadsComplete();

	/**
	 *  Tell to deiconify.
	 */
	public void restoreApplication();

    /**
     * @return true If the <code>guid</code> that maps to a query result screen 
     * is still available/viewable to the user.
     */
    public boolean isQueryAlive(GUID guid);
    
    /** Notification that installation may be corrupted. */
    public void installationCorrupted();
	
	/**
	 * The core passes parsed magnets to the callback and asks it if it wants
	 * to handle them itself.
	 * <p>
	 * If this is the case the callback should return <code>true</code>, otherwise
	 * the core starts the downloads itself.
	 * @param magnets Array of magnet information to handle
	 */
	public void handleMagnets(MagnetOptions[] magnets);

    /** Try to download the torrent file */
	public void handleTorrent(File torrentFile);

    /**
     * Translate a String taking into account Locale.
     * 
     * String literals that should be translated must still be marked for
     * translation using {@link I18nMarker#marktr(String)}.
     * 
     * @param s The String to translate
     * @return the translated String
     */
    public String translate(String s);
    
    /**
     * Handles the supplied SaveLocation exception by prompting the user for a new savelocation 
     * or whether to overwrite the file. 
     */
    void handleSaveLocationException(DownloadAction downLoadAction, SaveLocationException sle, boolean supportsNewSaveDir);
    
    /**
     * Validates with the user that the torrent upload should be cancelled. 
     * There are various reasons the user will not want the cancel to go through. 
     * 1) If the torrent is still downloading, the upload cannot be cancelled 
     *    without cancelling the download.
     * 2) If the torrent is seeding, but the seed ratio is low, the user may 
     *    wish to seed to at least 100% to be a good samaritan. 
     * @param torrent 
     */
    void promptTorrentUploadCancel(ManagedTorrent torrent);

}

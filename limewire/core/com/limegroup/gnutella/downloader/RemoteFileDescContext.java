package com.limegroup.gnutella.downloader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.auth.Credentials;
import org.limewire.collection.IntervalSet;
import org.limewire.io.Address;
import org.limewire.util.Objects;

import com.limegroup.gnutella.RemoteFileDesc;
import com.limegroup.gnutella.URN;

public class RemoteFileDescContext {
    
    private static final Log LOG = LogFactory.getLog(RemoteFileDescContext.class);

    private final RemoteFileDesc remoteFileDesc;
    
    /**
     * The number of times this download has failed while attempting
     * to transfer data.
     */
    private int failedCount;
    
    /**
     * The list of available ranges.
     * This is NOT SERIALIZED.
     */
    private IntervalSet availableRanges;
    
    /**
     * Whether or not THEX retrieval has failed with this host.
     */
    private boolean thexFailed;

    /**
     * The last known queue status of the remote host
     * negative values mean free slots
     */
    private int queueStatus = Integer.MAX_VALUE;

    /**
     * The earliest time to retry this host in milliseconds since 01-01-1970
     */
    private volatile long earliestRetryTime;

    public RemoteFileDescContext(RemoteFileDesc remoteFileDesc) {
        this.remoteFileDesc = Objects.nonNull(remoteFileDesc, "remoteFileDesc");
    }
    
    public RemoteFileDesc getRemoteFileDesc() {
        return remoteFileDesc;
    }

    /**
     * Returns the current failed count.
     */
    public int getFailedCount() {
        return failedCount;
    }
    
    /**
     * Increments the failed count by one.
     */
    public void incrementFailedCount() {
        ++failedCount;
    }
    
    /**
     * Resets the failed count back to zero.
     */
    public void resetFailedCount() {
        this.failedCount = 0;
    }

    /**
     * Accessor for the available ranges.
     */
    public IntervalSet getAvailableRanges() {
        try {
            return availableRanges.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Mutator for the available ranges.
     */
    public void setAvailableRanges(IntervalSet availableRanges) {
        this.availableRanges = availableRanges;
    }

    /**
     * Returns true if this is a partial source
     */
    public boolean isPartialSource() {
        return availableRanges != null;
    }
    /**
     * @return true if this host is still busy and should not be retried
     */
    public boolean isBusy() {
        return isBusy(System.currentTimeMillis());
    }

    public boolean isBusy(long now) {
        return now < earliestRetryTime;
    }

    /**
     * @return time to wait until this host will be ready to be retried
     * in seconds
     */
    public int getWaitTime(long now) {
        return isBusy(now) ? (int) (earliestRetryTime - now)/1000 + 1 : 0;
    }

    /**
     * Mutator for _earliestRetryTime. 
     * @param seconds number of seconds to wait before retrying
     */
    public void setRetryAfter(int seconds) {
        if(LOG.isDebugEnabled())
            LOG.debug("setting retry after to be [" + seconds + 
                      "] seconds for " + this);        
        earliestRetryTime = System.currentTimeMillis() + seconds*1000;
    }
    
    /**
     * @return Returns the _THEXFailed.
     */
    public boolean hasTHEXFailed() {
        return thexFailed;
    }

    /**
     * Having THEX with this host is no good. We can get our THEX from anybody,
     * so we won't bother again. 
     */
    public void setTHEXFailed() {
        thexFailed = true;
    }

    public URN getSHA1Urn() {
        return remoteFileDesc.getSHA1Urn();
    }

    public boolean isReplyToMulticast() {
        return remoteFileDesc.isReplyToMulticast();
    }

    public int getQueueStatus() {
        return queueStatus;
    }
    
    public void setQueueStatus(int queueStatus) {
        this.queueStatus = queueStatus;
    }
    
    public Address getAddress() {
        return remoteFileDesc.getAddress();
    }
    
    public Credentials getCredentials() {
        return remoteFileDesc.getCredentials();
    }
    
    public boolean isFromAlternateLocation() {
        return remoteFileDesc.isFromAlternateLocation();
    }
    
    @Override
    public String toString() {
        return remoteFileDesc.toString();
    }
}

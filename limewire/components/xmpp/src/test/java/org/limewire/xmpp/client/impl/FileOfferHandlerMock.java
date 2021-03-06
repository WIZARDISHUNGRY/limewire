package org.limewire.xmpp.client.impl;

import java.util.ArrayList;
import java.util.List;

import org.limewire.xmpp.api.client.FileMetaData;
import org.limewire.xmpp.api.client.FileOfferEvent;
import org.limewire.listener.RegisteringEventListener;
import org.limewire.listener.ListenerSupport;

import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class FileOfferHandlerMock implements RegisteringEventListener<FileOfferEvent> {

    List<FileMetaData> offers = new ArrayList<FileMetaData>();
    
    @Inject
    public void register(ListenerSupport<FileOfferEvent> fileOfferEventListenerSupport) {
        fileOfferEventListenerSupport.addListener(this);
    }

    public void handleEvent(FileOfferEvent event) {
        offers.add(event.getSource().getFile());
    }
}

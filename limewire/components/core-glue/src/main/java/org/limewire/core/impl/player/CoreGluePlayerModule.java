package org.limewire.core.impl.player;

import org.limewire.player.api.AudioPlayer;
import org.limewire.player.impl.LimeWirePlayer;

import com.google.inject.AbstractModule;

public class CoreGluePlayerModule extends AbstractModule {
    
    @Override
    protected void configure() {
        bind(AudioPlayer.class).to(LimeWirePlayer.class);
    }

}

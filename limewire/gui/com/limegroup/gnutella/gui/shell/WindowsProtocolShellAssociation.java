package com.limegroup.gnutella.gui.shell;

import java.io.IOException;

import org.limewire.util.SystemUtils;


/** A registration in the Windows Registry that sets a program as the default viewer for a custom Web link protocol. */
public class WindowsProtocolShellAssociation extends WindowsAssociation {

	private static final String HKCU = "HKEY_CURRENT_USER";
	
	private final String protocol;
	private final String name;

	/**
	 * Make a new WindowsProtocolShellAssociation, specifying the program and protocol it will check for and set.
	 * 
	 * @param running   The path to the native executable that is us running right now
	 * @param protocol The protocol without punctuation
	 * @param name      The common name of the protocol
	 */
	public WindowsProtocolShellAssociation(String executable, String protocol, String name) {
		super(executable);
		this.protocol = "Software\\Classes\\"+protocol;
		this.name = name;
	}

	@Override
    protected String get() throws IOException {
		String command = SystemUtils.registryReadText(HKCU, protocol + "\\shell\\open\\command", "");
		return parsePath(command);
		
	}

	public void register() {

		/*
		 * Create the following Registry keys and values:
		 * 
		 * Root                Key           Variable      Value
		 * ------------------  ------------  ------------  ---------------------------------------------
		 * HKEY_CLASSES_ROOT   protocol                      URL:name
		 *                                   URL Protocol
		 *                      DefaultIcon                executable,0
		 *                      shell
		 *                       open
		 *                        command                  executable "%1"
		 */
		SystemUtils.registryWriteText(HKCU, protocol,"","URL:" + name);
		SystemUtils.registryWriteText(HKCU, protocol,"URL Protocol", "");
		SystemUtils.registryWriteText(HKCU, protocol + "\\DefaultIcon","","\"" + executable + "\",0");
		SystemUtils.registryWriteText(HKCU, protocol + "\\shell\\open\\command", "","\"" + executable + "\" \"%1\"");
	}

	public void unregister() {
		SystemUtils.registryDelete(HKCU, protocol);
	}
}

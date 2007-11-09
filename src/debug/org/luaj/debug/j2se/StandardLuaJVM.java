/*******************************************************************************
* Copyright (c) 2007 LuaJ. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
******************************************************************************/
package org.luaj.debug.j2se;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.luaj.debug.DebugLuaState;
import org.luaj.debug.DebugSupport;
import org.luaj.debug.DebugUtils;
import org.luaj.debug.VMException;
import org.luaj.lib.CoroutineLib;
import org.luaj.lib.MathLib;
import org.luaj.lib.PackageLib;
import org.luaj.lib.StringLib;
import org.luaj.lib.TableLib;
import org.luaj.lib.j2se.LuajavaLib;
import org.luaj.vm.LClosure;
import org.luaj.vm.LPrototype;
import org.luaj.vm.LString;
import org.luaj.vm.LTable;
import org.luaj.vm.LValue;
import org.luaj.vm.LoadState;
import org.luaj.vm.LuaState;


/**
 * StandardLuaJVM executes a lua program in normal run mode or debug mode. 
 * 
 * @author:  Shu Lei
 * @version: 1.0
 */
public class StandardLuaJVM {
    protected boolean isDebugMode = false;
    protected int requestPort;
    protected int eventPort;
    protected String script;
    protected String[] scriptArgs;
    protected LuaState state;
    protected boolean isReady = false;
    protected boolean isTerminated = false;   
        
    // command line parsing utilities
    class ParseException extends Exception {
		private static final long serialVersionUID = -3134938136698856577L;

		public ParseException(String message) {
    		super(message);
    	}
    }
    
    protected void printUsage() {
        System.out.println("Usage:");
        System.out.println("\t java StandardLuaJVM [-debug <requestPort> <eventPort>] <script> [<script arguments>]");
        System.out.println("where [] denotes an optional argument and <> denotes a placeholder.");
    }
    
    void parse(String[] args) throws ParseException {
    	if (args == null || args.length < 1) {
    		throw new ParseException("Invalid command line arguments.");
    	}

    	if ("-debug".equals(args[0])) {
    		if (args.length < 4) {
    			throw new ParseException("Invalid command line arguments.");
    		}
    		
    		this.isDebugMode = true;
    		try {
	    		this.requestPort = Integer.parseInt(args[1]);
	            if (this.requestPort <= 0) {
	                throw new ParseException("Invalid request port: it must be greater than zero.");
	            }
	
	            this.eventPort = Integer.parseInt(args[2]);
	            if (this.eventPort <= 0) {
	                throw new ParseException("Invalid event port: it must be greater than zero.");
	            }
	    	} catch(NumberFormatException e) {
	            throw new ParseException("Invalid port number: " + e.getMessage());
	        } 
	    	
            if (this.requestPort == this.eventPort) {
                throw new ParseException("Invalid ports: request port and event port must be different");
            }
            
            int tempArgsCount = args.length - 3;
            String[] tempArgs = new String[tempArgsCount];
            System.arraycopy(args, 3, tempArgs, 0, tempArgsCount);	            
            parseScriptArgs(tempArgs);
    	} else {
    		parseScriptArgs(args);
    	}
    }

	private void parseScriptArgs(String[] args)
			throws org.luaj.debug.j2se.StandardLuaJVM.ParseException {
		if (args == null || args.length < 1) {
			throw new ParseException("script is missing.");
		}
		
	    this.script = args[0];
	    DebugUtils.println("Lua script to run: " + this.script);
	    int scriptArgsLength = args.length - 1;
	    if (scriptArgsLength > 0) {
	        this.scriptArgs = new String[scriptArgsLength];
	        for (int i = 1; i < args.length; i++) {
	            this.scriptArgs[i - 1] = args[i];
	        }            	
	    }
	}
    // end of command line parsing utilities
	
    boolean isDebug() {
        return this.isDebugMode;
    }
    
    int getRequestPort() {
        return this.requestPort;
    }
    
    int getEventPort() {
        return this.eventPort;
    }
    
    String getScript() {
        return this.script;
    }
    
    boolean hasScriptArgs() {
        return (this.scriptArgs != null && this.scriptArgs.length > 0);
    }
    
    String[] getScriptArgs() {
        return this.scriptArgs;
    }

    public void run() throws IOException {
        if (isDebug()) {
            doDebug();
        } else {
            doRun();
        }
    }
    
    protected void init(LuaState state) {
    	
        // add standard bindings
		state.installStandardLibs();
        
        // add LuaJava bindings
        LuajavaLib.install(state._G);        

    }
    
    protected void doRun() throws IOException {
        
        // new lua state 
        state = new LuaState();
        init(state);

        // convert args to lua
        String[] scriptArgs = getScriptArgs();
        int numOfScriptArgs = (scriptArgs == null) ? 0 : scriptArgs.length;
        LValue[] vargs = new LValue[numOfScriptArgs];
        for (int i = 0; i < numOfScriptArgs; i++) { 
            vargs[i] = new LString(getScriptArgs()[i]);
        }
        
        // load the Lua file
        DebugUtils.println("loading Lua script '" + getScript() + "'");
        InputStream is = new FileInputStream(new File(getScript()));
        LPrototype p = LoadState.undump(state, is, getScript());
        
        // create closure and execute
        LClosure c = new LClosure(state, p);
        state.doCall(c, vargs);        
    }
    
    protected void doDebug() throws IOException {        
        DebugUtils.println("setting up LuaJava and debug stack state...");        

        // new lua debug state 
        state = new DebugLuaState();
        init(state);

        // load the Lua file
        DebugUtils.println("loading Lua script '" + getScript() + "'");
        InputStream is = new FileInputStream(new File(getScript()));
        LPrototype p = LoadState.undump(state, is, getScript());

        // set up debug support if the file is successfully loaded
        DebugUtils.println("start debugging...");
        DebugSupport debugSupport 
        	= new DebugSupportImpl(getRequestPort(), getEventPort());
        getDebugState().setDebugSupport(debugSupport);
        getDebugState().setSuspendAtStart(true);
        
        // create closure and execute
        final LClosure c = new LClosure(p, state._G);
    	String[] args = getScriptArgs();
        int numOfScriptArgs = (args != null ? args.length : 0);
        LValue[] vargs = new LValue[numOfScriptArgs];
        for (int i = 0; i < numOfScriptArgs; i++) { 
            vargs[i] = new LString(args[i]);
        }
        try {
        	getDebugState().doCall(c, vargs);
        } catch (VMException e) {
        	System.err.println("VMException: " + e.getMessage());
        }
        getDebugState().stop();
    }   
    
    private DebugLuaState getDebugState() {
        return (DebugLuaState)state;
    }

    /**
     * Parses the command line arguments and executes/debugs the lua program.
     * @param args -- command line arguments: 
     *  [-debug <requestPort> <eventPort>] <script> <script arguments separated by a whitespace>
     */
    public static void main(String[] args) {
        StandardLuaJVM vm = new StandardLuaJVM();

        try {
            vm.parse(args);
            vm.run();
        } catch (ParseException e) {
            System.out.println("Error: " + e.getMessage());
            vm.printUsage(); 
            return;
        } catch (IOException e) {
        	System.out.println("Error: " + e.getMessage());
        	e.printStackTrace();
        }
    }
}

//
//   Copyright 2016  Cityzen Data
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.continuum.egress;

import io.warp10.continuum.BootstrapManager;
import io.warp10.continuum.Configuration;
import io.warp10.continuum.LogUtil;
import io.warp10.continuum.TimeSource;
import io.warp10.continuum.geo.GeoDirectoryClient;
import io.warp10.continuum.sensision.SensisionConstants;
import io.warp10.continuum.store.Constants;
import io.warp10.continuum.store.DirectoryClient;
import io.warp10.continuum.store.StoreClient;
import io.warp10.continuum.thrift.data.LoggingEvent;
import io.warp10.crypto.KeyStore;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptLib;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStopException;
import io.warp10.script.MemoryWarpScriptStack;
import io.warp10.script.StackUtils;
import io.warp10.script.WarpScriptStack.StackContext;
import io.warp10.sensision.Sensision;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLDecoder;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Singleton;

@Singleton
public class EgressExecHandler extends AbstractHandler {

  private static final Logger LOG = LoggerFactory.getLogger(EgressExecHandler.class);
  private static final Logger EVENTLOG = LoggerFactory.getLogger("warpscript.events");
  
  private final KeyStore keyStore;
  private final StoreClient storeClient;
  private final DirectoryClient directoryClient;
  private final GeoDirectoryClient geoDirectoryClient;

  private final BootstrapManager bootstrapManager;
  
  public EgressExecHandler(KeyStore keyStore, Properties properties, DirectoryClient directoryClient, GeoDirectoryClient geoDirectoryClient, StoreClient storeClient) {
    this.keyStore = keyStore;
    this.storeClient = storeClient;
    this.directoryClient = directoryClient;
    this.geoDirectoryClient = geoDirectoryClient;
    
    //
    // Check if we have a 'bootstrap' property
    //
    
    if (properties.containsKey(Configuration.CONFIG_WARPSCRIPT_BOOTSTRAP_PATH)) {
      
      final String path = properties.getProperty(Configuration.CONFIG_WARPSCRIPT_BOOTSTRAP_PATH);
      
      long period = properties.containsKey(Configuration.CONFIG_WARPSCRIPT_BOOTSTRAP_PERIOD) ?  Long.parseLong(properties.getProperty(Configuration.CONFIG_WARPSCRIPT_BOOTSTRAP_PERIOD)) : 0L;
      this.bootstrapManager = new BootstrapManager(path, period);      
    } else {
      this.bootstrapManager = new BootstrapManager();
    }
  }
  
  
  @Override
  public void handle(String target, Request baseRequest, HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {

    if (target.startsWith(Constants.API_ENDPOINT_EXEC)) {
      baseRequest.setHandled(true);
    } else {
      return;
    }
    
    //
    // CORS header
    //
    
    resp.setHeader("Access-Control-Allow-Origin", "*");

    //
    // Making the Elapsed header available in cross-domain context
    //

    resp.setHeader("Access-Control-Expose-Headers", Constants.getHeader(Configuration.HTTP_HEADER_ELAPSEDX));
    
    //
    // Generate UUID for this script execution
    //
    
    UUID uuid = UUID.randomUUID();
    
    //
    // Create EinsteinExecutionReport
    //
    
    //EinsteinExecutionReport report = new EinsteinExecutionReport();
    
    //
    // FIXME(hbs): Make sure we have at least one valid token
    //
    
    //
    // Create the stack to use
    //
    
    WarpScriptStack stack = new MemoryWarpScriptStack(this.storeClient, this.directoryClient, this.geoDirectoryClient);

    Throwable t = null;

    StringBuilder scriptSB = new StringBuilder();
    StringBuilder timeSB = new StringBuilder();
    
    int lineno = 0;

    // Labels for Sensision
    Map<String,String> labels = new HashMap<String,String>();

    long now = System.nanoTime();
    
    try {
      //
      // Replace the context with the bootstrap one
      //
      
      StackContext context = this.bootstrapManager.getBootstrapContext();
      
      if (null != context) {
        stack.push(context);
        stack.restore();
      }
      
      //
      // Execute the bootstrap code
      //

      stack.exec(WarpScriptLib.BOOTSTRAP);
      
      //
      // Extract parameters from the path info and set their value as symbols
      //
      
      String pathInfo = req.getPathInfo().substring(target.length());
      
      if (null != pathInfo && pathInfo.length() > 0) {
        pathInfo = pathInfo.substring(1);
        String[] tokens = pathInfo.split("/");

        for (String token: tokens) {
          String[] subtokens = token.split("=");
          
          subtokens[0] = URLDecoder.decode(subtokens[0], "UTF-8");
          subtokens[1] = URLDecoder.decode(subtokens[1], "UTF-8");        
          
          //
          // Execute values[0] so we can interpret it prior to storing it in the symbol table
          //
    
          scriptSB.append("// @param ").append(subtokens[0]).append("=").append(subtokens[1]).append("\n");

          stack.exec(subtokens[1]);
          
          stack.store(subtokens[0], stack.pop());
        }
      }
      
      //
      // Now read lines of the body, interpreting them
      //
      
      BufferedReader br = req.getReader();
                  
      labels.put(SensisionConstants.SENSISION_LABEL_THREAD, Long.toHexString(Thread.currentThread().getId()));
      
      List<Long> elapsed = (List<Long>) stack.getAttribute(WarpScriptStack.ATTRIBUTE_ELAPSED);
      
      elapsed.add(TimeSource.getNanoTime());
      
      boolean terminate = false;
      
      while(!terminate) {
        String line = br.readLine();
        
        if (null == line) {
          break;
        }

        lineno++;
        
        // Store line for logging purposes, BEFORE execution is attempted, so we know what line may have caused an exception
        scriptSB.append(line).append("\n");

        long nano = System.nanoTime();
        
        Sensision.set(SensisionConstants.SENSISION_CLASS_EINSTEIN_CURRENTEXEC_TIMESTAMP, labels, System.currentTimeMillis());
        try {
          stack.exec(line);
        } catch (WarpScriptStopException ese) {
          // Do nothing, this is simply an early termination which should not generate errors
          terminate = true;
        }
        
        Sensision.clear(SensisionConstants.SENSISION_CLASS_EINSTEIN_CURRENTEXEC_TIMESTAMP, labels);
      
        long end = System.nanoTime();

        // Record elapsed time
        if (Boolean.TRUE.equals(stack.getAttribute(WarpScriptStack.ATTRIBUTE_TIMINGS))) {
          elapsed.add(end - now);
        }
        
        timeSB.append(end - nano).append("\n");
      }

      //
      // Make sure stack is balanced
      //
      
      stack.checkBalanced();
      
      resp.setHeader(Constants.getHeader(Configuration.HTTP_HEADER_ELAPSEDX), Long.toString(System.nanoTime() - now));
      
      //resp.setContentType("application/json");
      //resp.setCharacterEncoding("UTF-8");
      
      //
      // Output the exported symbols in a map
      //
      
      Object exported = stack.getAttribute(WarpScriptStack.ATTRIBUTE_EXPORTED_SYMBOLS);
      
      if (null != exported && exported instanceof Set && !((Set) exported).isEmpty()) {
        Map<String,Object> exports = new HashMap<String,Object>();
        Map<String,Object> symtable = stack.getSymbolTable();
        for (Object symbol: (Set) exported) {
          if (null == symbol) {
            exports.putAll(symtable);
            break;
          }
          exports.put(symbol.toString(), symtable.get(symbol.toString()));
        }
        stack.push(exports);
      }
      
      StackUtils.toJSON(resp.getWriter(), stack);
    } catch (Exception e) {
      t = e;      
      
      if (t instanceof EmptyStackException) {
        t = new WarpScriptException("Empty stack", t);
      }
      
      int debugDepth = (int) stack.getAttribute(WarpScriptStack.ATTRIBUTE_DEBUG_DEPTH);

      resp.setHeader(Constants.getHeader(Configuration.HTTP_HEADER_ELAPSEDX), Long.toString(System.nanoTime() - now));
      resp.setHeader(Constants.getHeader(Configuration.HTTP_HEADER_ERROR_LINEX), Long.toString(lineno));
      resp.setHeader(Constants.getHeader(Configuration.HTTP_HEADER_ERROR_MESSAGEX), t.getMessage());
      
      //
      // Output the exported symbols in a map
      //
      
      Object exported = stack.getAttribute(WarpScriptStack.ATTRIBUTE_EXPORTED_SYMBOLS);
      
      if (null != exported && exported instanceof Set && !((Set) exported).isEmpty()) {
        Map<String,Object> exports = new HashMap<String,Object>();
        Map<String,Object> symtable = stack.getSymbolTable();
        for (Object symbol: (Set) exported) {
          if (null == symbol) {
            exports.putAll(symtable);
            break;
          }
          exports.put(symbol.toString(), symtable.get(symbol.toString()));
        }
        try { stack.push(exports); if (debugDepth < Integer.MAX_VALUE) { debugDepth++; } } catch (WarpScriptException wse) {}
      }

      if(debugDepth > 0) {        
        resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        PrintWriter pw = resp.getWriter();
        
        try { stack.push("ERROR line #" + lineno + ": " + t.getMessage() + (null != t.getCause() ? " (" + t.getCause().getMessage() + ")" : "")); if (debugDepth < Integer.MAX_VALUE) { debugDepth++; } } catch (WarpScriptException ee) {}

        try { StackUtils.toJSON(pw, stack, debugDepth); } catch (WarpScriptException ee) {}

      } else {
        throw new IOException("ERROR line #" + lineno + ": " + t.getMessage() + (null != t.getCause() ? " (" + t.getCause().getMessage() + ")" : ""));
      }
    } finally {
      // Clear this metric in case there was an exception
      Sensision.clear(SensisionConstants.SENSISION_CLASS_EINSTEIN_CURRENTEXEC_TIMESTAMP, labels);
      Sensision.update(SensisionConstants.SENSISION_CLASS_EINSTEIN_REQUESTS, Sensision.EMPTY_LABELS, 1);
      Sensision.update(SensisionConstants.SENSISION_CLASS_EINSTEIN_TIME_US, Sensision.EMPTY_LABELS, (long) ((System.nanoTime() - now) / 1000));
      Sensision.update(SensisionConstants.SENSISION_CLASS_EINSTEIN_OPS, Sensision.EMPTY_LABELS, (long) stack.getAttribute(WarpScriptStack.ATTRIBUTE_OPS));
      
      //
      // Record the JVM free memory
      //
      
      Sensision.set(SensisionConstants.SENSISION_CLASS_EINSTEIN_JVM_FREEMEMORY, Sensision.EMPTY_LABELS, Runtime.getRuntime().freeMemory());
      
      LoggingEvent event = LogUtil.setLoggingEventAttribute(null, LogUtil.WARPSCRIPT_SCRIPT, scriptSB.toString());
      event = LogUtil.setLoggingEventAttribute(event, LogUtil.WARPSCRIPT_TIMES, timeSB.toString());
      
      if (stack.isAuthenticated()) {
        event = LogUtil.setLoggingEventAttribute(event, WarpScriptStack.ATTRIBUTE_TOKEN, stack.getAttribute(WarpScriptStack.ATTRIBUTE_TOKEN).toString());        
      }
      
      if (null != t) {
        LogUtil.setLoggingEventStackTrace(null, LogUtil.STACK_TRACE, t);
        Sensision.update(SensisionConstants.SENSISION_CLASS_EINSTEIN_ERRORS, Sensision.EMPTY_LABELS, 1);
      }
      
      EVENTLOG.info(LogUtil.serializeLoggingEvent(this.keyStore, event));
    }
  }
}

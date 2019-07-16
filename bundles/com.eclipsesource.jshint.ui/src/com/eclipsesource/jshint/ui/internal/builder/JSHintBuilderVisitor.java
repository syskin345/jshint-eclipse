/*******************************************************************************
 * Copyright (c) 2012, 2013 EclipseSource.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Ralf Sternberg - initial implementation and API
 ******************************************************************************/
package com.eclipsesource.jshint.ui.internal.builder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.osgi.service.prefs.Preferences;

import com.eclipsesource.jshint.JSHint;
import com.eclipsesource.jshint.ProblemHandler;
import com.eclipsesource.jshint.Text;
import com.eclipsesource.jshint.ui.internal.Activator;
import com.eclipsesource.jshint.ui.internal.builder.JSHintBuilder.CoreExceptionWrapper;
import com.eclipsesource.jshint.ui.internal.preferences.EnablementPreferences;
import com.eclipsesource.jshint.ui.internal.preferences.JSHintPreferences;
import com.eclipsesource.jshint.ui.internal.preferences.PreferencesFactory;
import com.eclipsesource.jshint.ui.internal.preferences.ResourceSelector;


class JSHintBuilderVisitor implements IResourceVisitor, IResourceDeltaVisitor {

  private final ResourceSelector selector;
  private final IProgressMonitor monitor;

  private final IProject project;
  private final List<Future<?>> futures = new ArrayList<Future<?>>();
  private final ExecutorService service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

  private final AtomicInteger tasksStarted = new AtomicInteger();
  private final AtomicInteger tasksCompleted = new AtomicInteger();

  public JSHintBuilderVisitor( IProject project, IProgressMonitor monitor ) throws CoreException {
    this.project = project;
    Preferences node = PreferencesFactory.getProjectPreferences( project );
    new EnablementPreferences( node );
    selector = new ResourceSelector( project );
    this.monitor = monitor;
  }

  void close() throws CoreException {
    service.shutdown();
    try {
      service.awaitTermination(1, TimeUnit.DAYS);
    } catch (InterruptedException e1) {
      throw new RuntimeException(e1);
    }
    for (Future<?> f : futures) {
      try {
        f.get();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof CoreExceptionWrapper) {
          throw (CoreException)((CoreExceptionWrapper) cause).getCause();
        } else if (cause instanceof RuntimeException) {
          throw (RuntimeException) cause;
        }
        throw new RuntimeException(e);
      }
    }
  }

  public boolean visit( IResourceDelta delta ) throws CoreException {
    IResource resource = delta.getResource();
    return visit( resource );
  }

  public boolean visit( IResource resource ) throws CoreException {
    boolean descend = false;
    if( resource.exists() && selector.allowVisitProject() && !monitor.isCanceled() ) {
      if( resource.getType() != IResource.FILE ) {
        descend = selector.allowVisitFolder( resource );
      } else {
        clean( resource );
        if( selector.allowVisitFile( resource ) ) {
          check( (IFile)resource );
        }
        descend = true;
      }
    }
    return descend;
  }

  private static JSHint createJSHint( IProject project ) throws CoreException {
    JSHint jshint = new JSHint();
    try {
      InputStream inputStream = getCustomLib();
      if( inputStream != null ) {
        try {
          jshint.load( inputStream );
        } finally {
          inputStream.close();
        }
      } else {
        jshint.load();
      }
      jshint.configure( new ConfigLoader( project ).getConfiguration() );
    } catch( IOException exception ) {
      String message = "Failed to intialize JSHint";
      throw new CoreException( new Status( IStatus.ERROR, Activator.PLUGIN_ID, message, exception ) );
    }
    return jshint;
  }

  private ThreadLocal<JSHint> CHECKER_THREADLOCAL = new ThreadLocal<JSHint>() {
    @Override
    protected JSHint initialValue() {
      try {
        return selector.allowVisitProject() ? createJSHint(project) : null;
      } catch (CoreException e) {
        throw new CoreExceptionWrapper(e);
      }
    }
  };

  private void check(final IFile file)
  {
    tasksStarted.incrementAndGet();
    Future<?> future = service.submit(new Runnable() {
      public void run() {
        if (monitor.isCanceled()) {
          return;
        }
        monitor.setTaskName("JSHint checking file " + tasksCompleted.get() + " of " + tasksStarted.get());
        Text code;
        try {
          code = readContent( file );
        } catch (CoreException e) {
          throw new CoreExceptionWrapper(e);
        }
        JSHint checker = CHECKER_THREADLOCAL.get();
        if (checker != null) {
          ProblemHandler handler = new MarkerHandler( new MarkerAdapter(file), code );
          try {
            checker.check( code, handler );
          } catch (RuntimeException exception) {
            String message = "Failed checking file " + file.getFullPath().toPortableString();
            throw new RuntimeException( message, exception );
          }
        }
        tasksCompleted.incrementAndGet();
      }
    });
    futures.add(future);
  }

  private static void clean( IResource resource ) throws CoreException {
    new MarkerAdapter( resource ).removeMarkers();
  }

  private static InputStream getCustomLib() throws FileNotFoundException {
    JSHintPreferences globalPrefs = new JSHintPreferences();
    if( globalPrefs.getUseCustomLib() ) {
      File file = new File( globalPrefs.getCustomLibPath() );
      return new FileInputStream( file );
    }
    return null;
  }

  private static Text readContent( IFile file ) throws CoreException {
    try {
      InputStream inputStream = file.getContents();
      String charset = file.getCharset();
      return readContent( inputStream, charset );
    } catch( IOException exception ) {
      String message = "Failed to read resource";
      throw new CoreException( new Status( IStatus.ERROR, Activator.PLUGIN_ID, message, exception ) );
    }
  }

  private static Text readContent( InputStream inputStream, String charset )
      throws UnsupportedEncodingException, IOException
  {
    Text result;
    BufferedReader reader = new BufferedReader( new InputStreamReader( inputStream, charset ) );
    try {
      result = new Text( reader );
    } finally {
      reader.close();
    }
    return result;
  }

}

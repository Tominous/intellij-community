/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.cvsSupport2.changeBrowser;

import com.intellij.CvsBundle;
import com.intellij.cvsSupport2.CvsUtil;
import com.intellij.cvsSupport2.application.CvsEntriesManager;
import com.intellij.cvsSupport2.connections.CvsEnvironment;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor;
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback;
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler;
import com.intellij.cvsSupport2.history.CvsRevisionNumber;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.cvsIntegration.CvsResult;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.actions.VcsContextFactory;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.committed.*;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.versionBrowser.ChangeBrowserSettings;
import com.intellij.openapi.vcs.versionBrowser.ChangesBrowserSettingsEditor;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.AsynchConsumer;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.netbeans.lib.cvsclient.admin.Entry;
import org.netbeans.lib.cvsclient.command.log.Revision;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author yole
 */
public class CvsCommittedChangesProvider implements CachingCommittedChangesProvider<CvsChangeList, ChangeBrowserSettings> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.cvsSupport2.changeBrowser.CvsCommittedChangesProvider");

  private final Project myProject;
  private final MyZipper myZipper;

  @NonNls private static final String INVALID_OPTION_S = "invalid option -- S";

  public CvsCommittedChangesProvider(Project project) {
    myProject = project;
    myZipper = new MyZipper();
  }

  public ChangeBrowserSettings createDefaultSettings() {
    return new ChangeBrowserSettings();
  }

  public ChangesBrowserSettingsEditor<ChangeBrowserSettings> createFilterUI(final boolean showDateFilter) {
    return new CvsVersionFilterComponent(showDateFilter);
  }

  public VcsCommittedListsZipper getZipper() {
    return myZipper;
  }

  private static class MyZipper extends VcsCommittedListsZipperAdapter {
    private MyZipper() {
      super(new GroupCreator() {
        public Object createKey(final RepositoryLocation location) {
          final CvsRepositoryLocation cvsLocation = (CvsRepositoryLocation) location;
          return cvsLocation.getEnvironment().getRepository();
        }

        public RepositoryLocationGroup createGroup(final Object key, final Collection<RepositoryLocation> locations) {
          final RepositoryLocationGroup group = new RepositoryLocationGroup((String) key);
          for (RepositoryLocation location : locations) {
            group.add(location);
          }
          return group;
        }
      });
    }

    @Override
    public long getNumber(final CommittedChangeList list) {
      return list.getCommitDate().getTime();
    }
  }

  @Nullable
  public CvsRepositoryLocation getLocationFor(final FilePath root) {
    if (!CvsUtil.fileIsUnderCvs(root.getIOFile())) {
      return null;
    }
    final VirtualFile rootDir = root.isDirectory() ? root.getVirtualFile() : root.getVirtualFileParent();
    final String module = CvsUtil.getModuleName(root);
    final CvsEnvironment connectionSettings = CvsEntriesManager.getInstance().getCvsConnectionSettingsFor(rootDir);
    return new CvsRepositoryLocation(root.getVirtualFile(), connectionSettings, module);
  }

  public RepositoryLocation getLocationFor(final FilePath root, final String repositoryPath) {
    return getLocationFor(root);
  }

  public ChangeListColumn[] getColumns() {
    return new ChangeListColumn[] { ChangeListColumn.DATE, ChangeListColumn.NAME, ChangeListColumn.DESCRIPTION, BRANCH_COLUMN };
  }

  @Nullable
  public VcsCommittedViewAuxiliary createActions(final DecoratorManager manager, final RepositoryLocation location) {
    return null;
  }

  public int getUnlimitedCountValue() {
    return 0;
  }

  @Nullable
  @Override
  public Pair<CvsChangeList, FilePath> getOneList(VirtualFile file, final VcsRevisionNumber number) throws VcsException {
    final File ioFile = new File(file.getPath());
    final FilePath filePath = VcsContextFactory.SERVICE.getInstance().createFilePathOn(ioFile);
    final VirtualFile vcsRoot = ProjectLevelVcsManager.getInstance(myProject).getVcsRootFor(filePath);
    final CvsRepositoryLocation cvsLocation = getLocationFor(filePath);
    if (cvsLocation == null) return null;
    final String rootModule = CvsUtil.getModuleName(vcsRoot);
    final CvsEnvironment connectionSettings = cvsLocation.getEnvironment();
    if (connectionSettings.isOffline()) {
      return null;
    }
    final CvsChangeListsBuilder builder = new CvsChangeListsBuilder(rootModule, connectionSettings, myProject, vcsRoot);

    final CvsChangeList[] result = new CvsChangeList[1];
    final LoadHistoryOperation operation = new LoadHistoryOperation(connectionSettings, new Consumer<LogInformationWrapper>() {
      public void consume(LogInformationWrapper wrapper) {
        final List<Revision> revisions = wrapper.getRevisions();
        if (revisions.isEmpty()) return;
        final RevisionWrapper revision = new RevisionWrapper(wrapper.getFile(), revisions.get(0), null);
        result[0] = builder.addRevision(revision);
      }
    }, cvsLocation.getModuleName(), number.asString());
    final CvsResult executionResult = runRLogOperation(operation);

    if (executionResult.isCanceled()) {
      throw new ProcessCanceledException();
    }
    else if (! executionResult.hasNoErrors()) {
      throw executionResult.composeError();
    }
    if (result[0] == null) {
      return null;
    }
    final Date commitDate = result[0].getCommitDate();
    final CvsEnvironment rootConnectionSettings = CvsEntriesManager.getInstance().getCvsConnectionSettingsFor(vcsRoot);
    final long t = commitDate.getTime();
    final Date dateFrom = new Date(t - CvsChangeList.SUITABLE_DIFF);
    final Date dateTo = new Date(t + CvsChangeList.SUITABLE_DIFF);

    final LoadHistoryOperation operation2 =
      new LoadHistoryOperation(rootConnectionSettings, rootModule, dateFrom, dateTo, new Consumer<LogInformationWrapper>() {
        @Override
        public void consume(LogInformationWrapper wrapper) {
          final List<RevisionWrapper> wrappers = builder.revisionWrappersFromLog(wrapper);
          if (wrappers != null) {
            for (RevisionWrapper revisionWrapper : wrappers) {
              if (result[0].containsFileRevision(revisionWrapper)) {
                // otherwise a new change list will be created because the old change list already contains this file.
                continue;
              }
              builder.addRevision(revisionWrapper);
            }
          }
        }
      });
    final CvsResult cvsResult = runRLogOperation(operation2);
    if (!cvsResult.hasNoErrors()) {
      throw cvsResult.composeError();
    }
    return new Pair<CvsChangeList, FilePath>(result[0], filePath);
  }

  public List<CvsChangeList> getCommittedChanges(ChangeBrowserSettings settings, RepositoryLocation location, final int maxCount) throws VcsException {
    final CvsRepositoryLocation cvsLocation = (CvsRepositoryLocation) location;
    final String module = cvsLocation.getModuleName();
    final VirtualFile rootFile = cvsLocation.getRootFile();
    return loadCommittedChanges(settings, module, cvsLocation.getEnvironment(), rootFile);
  }

  public void loadCommittedChanges(ChangeBrowserSettings settings,
                                   RepositoryLocation location,
                                   int maxCount,
                                   final AsynchConsumer<CommittedChangeList> consumer)
    throws VcsException {
    try {
      final CvsRepositoryLocation cvsLocation = (CvsRepositoryLocation) location;
      final String module = cvsLocation.getModuleName();
      final CvsEnvironment connectionSettings = cvsLocation.getEnvironment();
      if (connectionSettings.isOffline()) {
        return;
      }
      final CvsChangeListsBuilder builder = new CvsChangeListsBuilder(module, connectionSettings, myProject, cvsLocation.getRootFile());
      final Date dateTo = settings.getDateBeforeFilter();
      Date dateFrom = settings.getDateAfterFilter();
      if (dateFrom == null) {
        final Calendar calendar = Calendar.getInstance();
        calendar.set(1970, 2, 2);
        dateFrom = calendar.getTime();
      }
      final ChangeBrowserSettings.Filter filter = settings.createFilter();
      final Set<CvsChangeList> controlSet = new HashSet<CvsChangeList>();
      final LoadHistoryOperation operation =
        new LoadHistoryOperation(connectionSettings, module, dateFrom, dateTo, new Consumer<LogInformationWrapper>() {
          public void consume(LogInformationWrapper wrapper) {
            final List<RevisionWrapper> wrappers = builder.revisionWrappersFromLog(wrapper);
            if (wrappers != null) {
              for (RevisionWrapper revisionWrapper : wrappers) {
                final CvsChangeList changeList = builder.addRevision(revisionWrapper);
                if (controlSet.contains(changeList)) continue;
                controlSet.add(changeList);
                if (filter.accepts(changeList)) {
                  consumer.consume(changeList);
                }
              }
            }
          }
        });
      final CvsResult executionResult = runRLogOperation(operation);

      if (executionResult.isCanceled()) {
        throw new ProcessCanceledException();
      }
      else if (!executionResult.hasNoErrors()) {
        throw executionResult.composeError();
      }
    }
    finally {
      consumer.finished();
    }
  }

  private List<CvsChangeList> loadCommittedChanges(final ChangeBrowserSettings settings,
                                                   final String module,
                                                   final CvsEnvironment connectionSettings,
                                                   final VirtualFile rootFile) throws VcsException {
    if (connectionSettings.isOffline()) {
      return Collections.emptyList();
    }
    final CvsChangeListsBuilder builder = new CvsChangeListsBuilder(module, connectionSettings, myProject, rootFile);
    Date dateTo = settings.getDateBeforeFilter();
    Date dateFrom = settings.getDateAfterFilter();
    if (dateFrom == null) {
      final Calendar calendar = Calendar.getInstance();
      calendar.set(1970, 2, 2);
      dateFrom = calendar.getTime();
    }
    final List<LogInformationWrapper> log = new ArrayList<LogInformationWrapper>();
    final LoadHistoryOperation operation =
      new LoadHistoryOperation(connectionSettings, module, dateFrom, dateTo, new Consumer<LogInformationWrapper>() {
        @Override
        public void consume(LogInformationWrapper logInformationWrapper) {
          log.add(logInformationWrapper);
        }
      });
    final CvsResult executionResult = runRLogOperation(operation);

    if (executionResult.isCanceled()) {
      throw new ProcessCanceledException();
    }
    else if (!executionResult.hasNoErrors()) {
      throw executionResult.composeError();
    }
    else {
      builder.addLogs(log);
      final List<CvsChangeList> versions = builder.getVersions();
      settings.filterChanges(versions);
      return versions;
    }
  }

  private CvsResult runRLogOperation(final LoadHistoryOperation operation) {
    final CvsResult executionResult = runRLogOperationImpl(operation);

    for (VcsException error : executionResult.getErrors()) {
      for (String message : error.getMessages()) {
        if (message.contains(INVALID_OPTION_S)) {
          operation.disableSuppressEmptyHeadersForCurrentCvsRoot();
          // try only once
          return runRLogOperationImpl(operation);
        }
      }
    }
    return executionResult;
  }

  private CvsResult runRLogOperationImpl(final LoadHistoryOperation operation) {
    final CvsOperationExecutor executor = new CvsOperationExecutor(myProject);
    executor.performActionSync(new CommandCvsHandler(CvsBundle.message("browse.changes.load.history.progress.title"), operation),
                               CvsOperationExecutorCallback.EMPTY);
    return executor.getResult();
  }

  public int getFormatVersion() {
    return 3;
  }

  public void writeChangeList(final DataOutput stream, final CvsChangeList list) throws IOException {
    list.writeToStream(stream);
  }

  public CvsChangeList readChangeList(final RepositoryLocation location, final DataInput stream) throws IOException {
    final CvsRepositoryLocation cvsLocation = (CvsRepositoryLocation) location;
    return new CvsChangeList(myProject, cvsLocation.getEnvironment(), cvsLocation.getRootFile(), stream);
  }

  public boolean isMaxCountSupported() {
    return false;
  }

  public Collection<FilePath> getIncomingFiles(final RepositoryLocation location) {
    return null;
  }

  public boolean refreshCacheByNumber() {
    return false;
  }

  public String getChangelistTitle() {
    return null;
  }

  public boolean isChangeLocallyAvailable(final FilePath filePath, @Nullable VcsRevisionNumber localRevision, VcsRevisionNumber changeRevision,
                                          final CvsChangeList changeList) {
    if (localRevision instanceof CvsRevisionNumber && changeRevision instanceof CvsRevisionNumber) {
      final CvsRevisionNumber cvsLocalRevision = (CvsRevisionNumber)localRevision;
      final CvsRevisionNumber cvsChangeRevision = (CvsRevisionNumber)changeRevision;
      final int[] localSubRevisions = cvsLocalRevision.getSubRevisions();
      final int[] changeSubRevisions = cvsChangeRevision.getSubRevisions();
      if (localSubRevisions != null && changeSubRevisions != null) {
        if (localSubRevisions.length != changeSubRevisions.length) {
          // local is trunk, change is branch / vice versa
          return true;
        }
        for(int i=2; i<localSubRevisions.length; i += 2) {
          if (localSubRevisions [i] != changeSubRevisions [i]) {
            // local is one branch, change is a different branch
            return true;
          }
        }
      }
    }

    return isDifferentBranch(filePath, changeList) || (localRevision != null && localRevision.compareTo(changeRevision) >= 0);
  }

  private static boolean isDifferentBranch(final FilePath filePath, final CvsChangeList changeList) {
    final String localTag;
    final CvsEntriesManager cvsEntriesManager = CvsEntriesManager.getInstance();
    final VirtualFile parent = filePath.getVirtualFileParent();
    if (parent != null) {
      final Entry entry = cvsEntriesManager.getEntryFor(parent, filePath.getName());
      if (entry != null) {
        localTag = entry.getStickyTag();
      }
      else {
        localTag = getDirectoryTag(parent);
      }
    }
    else {
      final VirtualFile validParent = ApplicationManager.getApplication().runReadAction(new Computable<VirtualFile>() {
        @Nullable
        public VirtualFile compute() {
          return ChangesUtil.findValidParent(filePath);
        }
      });
      if (validParent == null) return false;
      localTag = getDirectoryTag(validParent);
    }
    final String remoteTag = changeList.getBranch();
    if (!Comparing.equal(localTag, remoteTag)) {
      LOG.info(filePath + ": local tag " + localTag + ", remote tag " + remoteTag);
      return true;
    }
    return false;
  }

  @Nullable
  private static String getDirectoryTag(@NotNull final VirtualFile parent) {
    final String dirTag = CvsEntriesManager.getInstance().getCvsInfoFor(parent).getStickyTag();
    if (dirTag == null || !CvsUtil.isNonDateTag(dirTag)) return null;
    return dirTag.substring(1);
  }

  public boolean refreshIncomingWithCommitted() {
    return true;
  }

  private final ChangeListColumn<CvsChangeList> BRANCH_COLUMN = new ChangeListColumn<CvsChangeList>() {
    public String getTitle() {
      return CvsBundle.message("changelist.column.branch");
    }

    public Object getValue(final CvsChangeList changeList) {
      final String branch = changeList.getBranch();
      return branch == null ? "HEAD" : branch;
    }
  };
}

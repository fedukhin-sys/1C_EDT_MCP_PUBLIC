package ru.fedukhin.edt.mcp.tools.infobase.internal;

import com._1c.g5.designer.ssh.client.operation.IDbStructureChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseConfigurationChange;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateConflictResolver;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseConflictResolutionResult;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSynchronizationException;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationFlow;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.List;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;

/**
 * Non-interactive {@link IInfobaseUpdateCallback} for headless deploy:
 * onConfirm accepts all DB structure changes; resolveInfobaseChanges returns
 * {@link InfobaseConflictResolutionResult#IGNORED} — we do not decide for the
 * user; any non-trivial divergence must surface via {@link InfobaseSynchronizationException}
 * thrown by the EDT pipeline itself.
 */
public class NoopUpdateCallback implements IInfobaseUpdateCallback {

    @Override
    public boolean onConfirm(IProject project, InfobaseReference ref,
                              List<IDbStructureChange> changes, IProgressMonitor monitor) {
        return true;
    }

    @Override
    public InfobaseConflictResolutionResult resolveInfobaseChanges(
            IProject project, InfobaseReference ref,
            Set<EObject> a, Set<EObject> b,
            IInfobaseConfigurationChange change,
            IInfobaseUpdateConflictResolver resolver,
            IInfobaseUpdateConflictResolver.IConflictResolveAssist assist,
            IInfobaseSynchronizationFlow flow,
            IProgressMonitor monitor) throws InfobaseSynchronizationException {
        // IGNORED: headless mode skips interactive conflict resolution;
        // real conflicts must be surfaced by the EDT synchronization pipeline.
        return InfobaseConflictResolutionResult.IGNORED;
    }
}

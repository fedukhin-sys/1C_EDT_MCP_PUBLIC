package ru.fedukhin.edt.mcp.tools.debug.internal;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import org.eclipse.debug.core.ILaunch;

/**
 * What {@link DebugLauncher#launch} produces: the connected debug target, the bare {@link ILaunch}
 * it lives on, the attached client {@link Process}, and the infobase name + client type — all the
 * raw material {@code debug_client} needs to build a {@link DebugSession}.
 */
public record DebugLaunchResult(IRuntimeDebugClientTarget target, ILaunch launch,
                                Process clientProcess, String infobaseName, String clientType) {}

/*
 * Copyright (c) 2014, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.truffle.runtime.debug;

import java.util.LinkedHashMap;
import java.util.Map;

import org.graalvm.compiler.truffle.runtime.GraalTruffleRuntime;
import org.graalvm.compiler.truffle.runtime.GraalTruffleRuntimeListener;
import org.graalvm.compiler.truffle.runtime.OptimizedCallTarget;
import org.graalvm.compiler.truffle.runtime.TruffleDebugOptions;

/**
 * Traces Truffle compilation failures.
 */
public final class TraceCompilationFailureListener implements GraalTruffleRuntimeListener {

    GraalTruffleRuntime runtime;

    private TraceCompilationFailureListener(GraalTruffleRuntime runtime) {
        this.runtime = runtime;
    }

    public static void install(GraalTruffleRuntime runtime) {
        runtime.addListener(new TraceCompilationFailureListener(runtime));
    }

    @Override
    public void onCompilationFailed(OptimizedCallTarget target, String reason, boolean bailout, boolean permanentBailout) {
        if (!bailout || TruffleDebugOptions.verboseBailouts()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("ASTSize", target.getNonTrivialNodeCount());
            properties.put("Reason", reason);
            runtime.logEvent(0, "opt fail", target.toString(), properties);
        }
    }
}

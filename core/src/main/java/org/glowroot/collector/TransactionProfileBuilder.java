/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.collector;

import java.util.List;

import com.google.common.base.Objects;
import org.checkerframework.dataflow.qual.Pure;

import org.glowroot.markers.GuardedBy;
import org.glowroot.trace.model.Profile;
import org.glowroot.trace.model.ProfileNode;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class TransactionProfileBuilder {

    private final Object lock = new Object();
    @GuardedBy("lock")
    private final ProfileNode syntheticRootNode = ProfileNode.createSyntheticRoot();

    Object getLock() {
        return lock;
    }

    // must be holding lock to call and can only use resulting node tree inside the same
    // synchronized block
    ProfileNode getSyntheticRootNode() {
        return syntheticRootNode;
    }

    void addProfile(Profile profile) {
        synchronized (lock) {
            synchronized (profile.getLock()) {
                mergeNode(syntheticRootNode, profile.getSyntheticRootNode());
            }
        }
    }

    private void mergeNode(ProfileNode node, ProfileNode toBeMergedNode) {
        node.incrementSampleCount(toBeMergedNode.getSampleCount());
        // the trace metric names for a given stack element should always match, unless
        // the line numbers aren't available and overloaded methods are matched up, or
        // the stack trace was captured while one of the synthetic $trace$metric$ methods was
        // executing in which case one of the trace metric names may be a subset of the other,
        // in which case, the superset wins:
        List<String> traceMetrics = toBeMergedNode.getTraceMetrics();
        if (traceMetrics != null && traceMetrics.size() > node.getTraceMetrics().size()) {
            node.setTraceMetrics(traceMetrics);
        }
        for (ProfileNode toBeMergedChildNode : toBeMergedNode.getChildNodes()) {
            // for each to-be-merged child node look for a match
            ProfileNode foundMatchingChildNode = null;
            for (ProfileNode childNode : node.getChildNodes()) {
                if (matches(toBeMergedChildNode, childNode)) {
                    foundMatchingChildNode = childNode;
                    break;
                }
            }
            if (foundMatchingChildNode == null) {
                // since to-be-merged nodes may still be used when storing the trace, need to make
                // copy here
                StackTraceElement stackTraceElement = toBeMergedChildNode.getStackTraceElement();
                // stackTraceElement is only null for synthetic root
                checkNotNull(stackTraceElement);
                foundMatchingChildNode = ProfileNode.create(stackTraceElement,
                        toBeMergedChildNode.getLeafThreadState());
                node.addChildNode(foundMatchingChildNode);
            }
            mergeNode(foundMatchingChildNode, toBeMergedChildNode);
        }
    }

    private boolean matches(ProfileNode node1, ProfileNode node2) {
        return Objects.equal(node1.getStackTraceElement(), node2.getStackTraceElement())
                && Objects.equal(node1.getLeafThreadState(), node2.getLeafThreadState());
    }

    @Override
    @Pure
    public String toString() {
        return Objects.toStringHelper(this)
                .add("syntheticRootNode", syntheticRootNode)
                .toString();
    }
}
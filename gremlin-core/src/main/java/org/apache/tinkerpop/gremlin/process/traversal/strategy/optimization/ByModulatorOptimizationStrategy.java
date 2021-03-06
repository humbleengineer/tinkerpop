/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.IdentityTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.TokenTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.lambda.ValueTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.ByModulating;
import org.apache.tinkerpop.gremlin.process.traversal.step.Grouping;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.FoldStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.GroupStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.IdStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.LabelStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertiesStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyKeyStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PropertyValueStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.GroupSideEffectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.structure.PropertyType;
import org.apache.tinkerpop.gremlin.structure.T;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This strategy looks for standard traversals in by-modulators and replaces them with more optimized traversals
 * (e.g. {@code TokenTraversal}) if possible.
 * <p/>
 *
 * @author Daniel Kuppitz (http://gremlin.guru)
 * @author Stephen Mallette (http://stephen.genoprime.com)
 * @example <pre>
 * __.path().by(id())                        // is replaced by __.path().by(id)
 * __.dedup().by(label())                    // is replaced by __.dedup().by(label)
 * __.order().by(values("name"))             // is replaced by __.order().by("name")
 * __.group().by().by(values("name").fold()) // is replaced by __.group().by("name")
 * </pre>
 */
public final class ByModulatorOptimizationStrategy extends AbstractTraversalStrategy<TraversalStrategy.OptimizationStrategy>
        implements TraversalStrategy.OptimizationStrategy {

    private static final ByModulatorOptimizationStrategy INSTANCE = new ByModulatorOptimizationStrategy();

    // when PathProcessorStrategy is present for withComputer() you need to ensure it always executes first because
    // it does some manipulation to select().by(t) in some cases to turn it to select().map(t) in which case this
    // strategy has nothing to do. if it were to occur first then that optimization wouldn't work as expected.
    private static final Set<Class<? extends OptimizationStrategy>> PRIORS = new HashSet<>(Arrays.asList(
            PathProcessorStrategy.class, IdentityRemovalStrategy.class));

    private ByModulatorOptimizationStrategy() {
    }

    public static ByModulatorOptimizationStrategy instance() {
        return INSTANCE;
    }

    private void optimizeByModulatingTraversal(final TraversalParent step, final Traversal.Admin<?, ?> traversal) {
        if (traversal == null) return;
        final List<Step> steps = traversal.asAdmin().getSteps();
        if (steps.size() == 1) {
            final Step singleStep = steps.get(0);
            optimizeForStep(step, traversal, singleStep);
        }
    }

    private void optimizeForStep(final TraversalParent step, final Traversal.Admin<?, ?> traversal, final Step singleStep) {
        if (singleStep instanceof PropertiesStep) {
            final PropertiesStep ps = (PropertiesStep) singleStep;
            if (ps.getReturnType().equals(PropertyType.VALUE) && ps.getPropertyKeys().length == 1) {
                step.replaceLocalChild(traversal, new ValueTraversal<>(ps.getPropertyKeys()[0]));
            }
        } else if (singleStep instanceof IdStep) {
            step.replaceLocalChild(traversal, new TokenTraversal<>(T.id));
        } else if (singleStep instanceof LabelStep) {
            step.replaceLocalChild(traversal, new TokenTraversal<>(T.label));
        } else if (singleStep instanceof PropertyKeyStep) {
            step.replaceLocalChild(traversal, new TokenTraversal<>(T.key));
        } else if (singleStep instanceof PropertyValueStep) {
            step.replaceLocalChild(traversal, new TokenTraversal<>(T.value));
        } else if (singleStep instanceof IdentityStep) {
            step.replaceLocalChild(traversal, new IdentityTraversal<>());
        }
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        final Step step = traversal.getParent().asStep();
        if (step instanceof ByModulating && step instanceof TraversalParent) {
            final TraversalParent byModulatingStep = (TraversalParent) step;
            if (step instanceof Grouping) {
                final Grouping grouping = (Grouping) step;
                optimizeByModulatingTraversal(byModulatingStep, grouping.getKeyTraversal());

                // the value by() needs different handling because by(Traversal) only equals by(String) or by(T)
                // if the traversal does a fold().
                final Traversal.Admin<?, ?> currentValueTraversal = grouping.getValueTraversal();
                final List<Step> stepsInCurrentValueTraversal = currentValueTraversal.getSteps();
                if (stepsInCurrentValueTraversal.size() == 1 && stepsInCurrentValueTraversal.get(0) instanceof IdentityStep)
                    optimizeForStep(byModulatingStep, currentValueTraversal, stepsInCurrentValueTraversal.get(0));
                else if (stepsInCurrentValueTraversal.size() == 2 && stepsInCurrentValueTraversal.get(1) instanceof FoldStep)
                    optimizeForStep(byModulatingStep, currentValueTraversal, stepsInCurrentValueTraversal.get(0));

            } else {
                for (final Traversal.Admin<?, ?> byModulatingTraversal : byModulatingStep.getLocalChildren()) {
                    optimizeByModulatingTraversal(byModulatingStep, byModulatingTraversal);
                }
            }
        }
    }

    @Override
    public Set<Class<? extends OptimizationStrategy>> applyPrior() {
        return PRIORS;
    }
}

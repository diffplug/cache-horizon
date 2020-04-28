/*
 * Copyright 2020 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.cachehorizon;


import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.caching.internal.controller.BuildCacheController;
import org.gradle.caching.internal.origin.OriginMetadata;
import org.gradle.execution.plan.LocalTaskNode;
import org.gradle.execution.plan.Node;
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal;
import org.gradle.internal.Try;
import org.gradle.internal.execution.CachingContext;
import org.gradle.internal.execution.CachingResult;
import org.gradle.internal.execution.ExecutionOutcome;
import org.gradle.internal.execution.ExecutionRequestContext;
import org.gradle.internal.execution.Step;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.UpToDateResult;
import org.gradle.internal.execution.impl.DefaultWorkExecutor;
import org.gradle.internal.execution.steps.CaptureStateBeforeExecutionStep;
import org.gradle.internal.execution.steps.LoadExecutionStateStep;
import org.gradle.internal.execution.steps.ResolveCachingStateStep;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;
import org.gradle.internal.fingerprint.overlap.OverlappingOutputDetector;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList;
import org.gradle.internal.impldep.com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.snapshot.ValueSnapshotter;

public class CacheHorizonPlugin implements Plugin<Project> {
	@Override
	public void apply(Project project) {
		project.getExtensions().create("cacheHorizon", Extension.class, project);
	}

	public class Extension extends HorizonConfig {
		public Extension(Project project) {
			super(project, "cacheHorizon");
		}

		public HorizonConfig named(String horizonName, Action<HorizonConfig> config) {
			HorizonConfig horizon = new HorizonConfig(project, horizonName);
			config.execute(horizon);
			return horizon;
		}
	}

	public class HorizonConfig {
		protected final Project project;
		private final TaskProvider<?> horizonTask;
		private final TaskProvider<?> horizonIsCached;
		private final List<Provider<? extends Task>> innerTasks = new ArrayList<>();

		private HorizonConfig(Project project, String name) {
			this.project = project;
			horizonTask = project.getTasks().register(name);
			horizonIsCached = project.getTasks().register(name + "IsCached", HorizonIsCached.class, isCachedTask -> {
				isCachedTask.horizonTask = horizonTask;
				isCachedTask.innerTasks = innerTasks;
			});
		}

		public void add(Object... tasks) {
			for (Object task : tasks) {
				if (task instanceof String) {
					addTask(project.getTasks().named((String) task));
				} else if (task instanceof TaskProvider) {
					addTask((TaskProvider<?>) task);
				} else if (task instanceof Task) {
					addTask((Task) task);
				} else {
					throw new IllegalArgumentException("Must be a String, TaskProvider, or Task, this was " + (task == null ? null : task.getClass()));
				}
			}
		}

		private void addTask(TaskProvider<?> innerTask) {
			horizonTask.configure(horizon -> horizon.dependsOn(innerTask));
			innerTask.configure(inner -> inner.dependsOn(horizonIsCached));
			innerTasks.add(innerTask);
		}

		private void addTask(Task innerTask) {
			horizonTask.configure(horizon -> horizon.dependsOn(innerTask));
			innerTask.dependsOn(horizonIsCached);
			innerTasks.add(project.getProviders().provider(() -> innerTask));
		}

		public void inputsAndOutputs(Action<Task> configureHorizonManually) {
			horizonTask.configure(task -> {
				task.getOutputs().cacheIf(unused2 -> true);
				configureHorizonManually.execute(task);
			});
		}
	}

	public static class HorizonIsCached extends DefaultTask {
		private TaskProvider<?> horizonTask;
		private List<Provider<? extends Task>> innerTasks;

		// disable all inner tasks if the horizonTask can be restored from cache
		@TaskAction
		public void execute() {
			if (isCachedOrUpToDate(horizonTask.get())) {
				for (Provider<? extends Task> innerProvider : innerTasks) {
					Task innerTask = innerProvider.get();
					innerTask.setEnabled(false);
				}
			}
		}

		private boolean isCachedOrUpToDate(Task task) {
			TaskExecutionGraphInternal graph = (TaskExecutionGraphInternal) getProject().getGradle().getTaskGraph();
			for (Node node : graph.getScheduledWork()) {
				if (node instanceof LocalTaskNode) {
					LocalTaskNode localTaskNode = (LocalTaskNode) node;
					if (localTaskNode.getTask() == horizonTask) {
						return isCachedOrUpToDate(localTaskNode);
					}
				}
			}
			return false;
		}

		private boolean isCachedOrUpToDate(LocalTaskNode localTaskNode) {
			ServiceRegistry registry = ((ProjectInternal) getProject()).getServices();

			BuildOperationExecutor buildOperationExecutor = registry.get(BuildOperationExecutor.class);
			ClassLoaderHierarchyHasher classLoaderHierarchyHasher = registry.get(ClassLoaderHierarchyHasher.class);
			ValueSnapshotter valueSnapshotter = registry.get(ValueSnapshotter.class);
			OverlappingOutputDetector overlappingOutputDetector = registry.get(OverlappingOutputDetector.class);
			BuildCacheController buildCacheController = registry.get(BuildCacheController.class);
			boolean buildScansEnabled = false;
			DefaultWorkExecutor<ExecutionRequestContext, CachingResult> executor = new DefaultWorkExecutor<>(
					new LoadExecutionStateStep<>(
							new CaptureStateBeforeExecutionStep(buildOperationExecutor, classLoaderHierarchyHasher, valueSnapshotter, overlappingOutputDetector,
									new ResolveCachingStateStep(buildCacheController, buildScansEnabled, new Step<CachingContext, UpToDateResult>() {
										@Override
										public UpToDateResult execute(CachingContext context) {
											return new UpToDateResult() {
												@Override
												public ImmutableSortedMap<String, ? extends FileCollectionFingerprint> getFinalOutputs() {
													throw new UnsupportedOperationException();
												}

												@Override
												public Try<ExecutionOutcome> getOutcome() {
													throw new UnsupportedOperationException();
												}

												@Override
												public ImmutableList<String> getExecutionReasons() {
													throw new UnsupportedOperationException();
												}

												@Override
												public Optional<OriginMetadata> getReusedOutputOriginMetadata() {
													throw new UnsupportedOperationException();
												}
											};
										}
									}))));
			CachingResult caching = executor.execute(new ExecutionRequestContext() {
				@Override
				public Optional<String> getRebuildReason() {
					throw new UnsupportedOperationException();
				}

				@Override
				public UnitOfWork getWork() {
					throw new UnsupportedOperationException();
				}
			});
			ExecutionOutcome outcome = caching.getOutcome().get();
			return outcome == ExecutionOutcome.FROM_CACHE || outcome == ExecutionOutcome.UP_TO_DATE;
		}
	}
}

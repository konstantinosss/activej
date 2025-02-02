/*
 * Copyright (C) 2020 ActiveJ LLC.
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

package io.activej.launchers.crdt.rpc;

import io.activej.async.function.AsyncSupplier;
import io.activej.async.service.EventloopService;
import io.activej.crdt.storage.cluster.DiscoveryService;
import io.activej.eventloop.Eventloop;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.rpc.client.RpcClient;
import io.activej.rpc.client.sender.RpcStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

import static io.activej.common.Checks.checkNotNull;
import static io.activej.common.Checks.checkState;

public final class CrdtRpcStrategyService<K extends Comparable<K>> implements EventloopService {
	private final Eventloop eventloop;
	private final DiscoveryService<?> discoveryService;
	private final Function<Object, K> keyGetter;

	private RpcClient rpcClient;
	private Function<RpcStrategy, RpcStrategy> strategyMapFn = Function.identity();

	private boolean stopped;

	private CrdtRpcStrategyService(Eventloop eventloop, DiscoveryService<?> discoveryService, Function<Object, K> keyGetter) {
		this.eventloop = eventloop;
		this.discoveryService = discoveryService;
		this.keyGetter = keyGetter;
	}

	public static <K extends Comparable<K>> CrdtRpcStrategyService<K> create(Eventloop eventloop, DiscoveryService<?> discoveryService, Function<Object, K> keyGetter) {
		return new CrdtRpcStrategyService<>(eventloop, discoveryService, keyGetter);
	}

	public CrdtRpcStrategyService<K> withStrategyMapping(Function<RpcStrategy, RpcStrategy> strategyMapFn) {
		this.strategyMapFn = strategyMapFn;
		return this;
	}

	public void setRpcClient(RpcClient rpcClient) {
		checkState(this.rpcClient == null && rpcClient.getEventloop() == eventloop);

		this.rpcClient = rpcClient;
	}

	@Override
	public @NotNull Eventloop getEventloop() {
		return eventloop;
	}

	@Override
	public @NotNull Promise<?> start() {
		checkNotNull(rpcClient);

		AsyncSupplier<? extends DiscoveryService.PartitionScheme<?>> discoverySupplier = discoveryService.discover();
		return discoverySupplier.get()
				.whenResult(partitionScheme -> {
					RpcStrategy rpcStrategy = partitionScheme.createRpcStrategy(keyGetter);
					rpcClient.withStrategy(strategyMapFn.apply(rpcStrategy));
					Promises.repeat(() ->
							discoverySupplier.get()
									.map((newPartitionScheme, e) -> {
										if (stopped) return false;
										if (e == null) {
											RpcStrategy newRpcStrategy = newPartitionScheme.createRpcStrategy(keyGetter);
											rpcClient.changeStrategy(strategyMapFn.apply(newRpcStrategy), true);
										}
										return true;
									})
					);
				});

	}

	@Override
	public @NotNull Promise<?> stop() {
		this.stopped = true;
		return Promise.complete();
	}
}

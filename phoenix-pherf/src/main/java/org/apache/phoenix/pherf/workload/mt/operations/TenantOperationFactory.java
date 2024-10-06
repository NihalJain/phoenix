/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.pherf.workload.mt.operations;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.pherf.configuration.DataModel;
import org.apache.phoenix.pherf.configuration.Ddl;
import org.apache.phoenix.pherf.configuration.IdleTime;
import org.apache.phoenix.pherf.configuration.LoadProfile;
import org.apache.phoenix.pherf.configuration.Query;
import org.apache.phoenix.pherf.configuration.QuerySet;
import org.apache.phoenix.pherf.configuration.Scenario;
import org.apache.phoenix.pherf.configuration.TenantGroup;
import org.apache.phoenix.pherf.configuration.Upsert;
import org.apache.phoenix.pherf.configuration.UserDefined;
import org.apache.phoenix.pherf.configuration.XMLConfigParser;
import org.apache.phoenix.pherf.rules.RulesApplier;
import org.apache.phoenix.pherf.util.PhoenixUtil;
import org.apache.phoenix.pherf.workload.mt.MultiTenantWorkload;
import org.apache.phoenix.pherf.workload.mt.generators.LoadEventGenerator;
import org.apache.phoenix.pherf.workload.mt.generators.TenantOperationInfo;
import org.apache.phoenix.pherf.workload.mt.handlers.TenantOperationWorkHandler;
import org.apache.phoenix.thirdparty.com.google.common.base.Charsets;
import org.apache.phoenix.thirdparty.com.google.common.base.Function;
import org.apache.phoenix.thirdparty.com.google.common.base.Supplier;
import org.apache.phoenix.thirdparty.com.google.common.collect.Lists;
import org.apache.phoenix.thirdparty.com.google.common.collect.Maps;
import org.apache.phoenix.thirdparty.com.google.common.hash.BloomFilter;
import org.apache.phoenix.thirdparty.com.google.common.hash.Funnel;
import org.apache.phoenix.thirdparty.com.google.common.hash.PrimitiveSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory class for operation suppliers. The class is responsible for creating new instances of
 * suppliers {@link Supplier} for operations {@link Operation} Operations that need to be executed
 * for a given {@link Scenario} and {@link DataModel} are generated by {@link LoadEventGenerator}
 * These operation events are then published on to the {@link com.lmax.disruptor.RingBuffer} by the
 * {@link MultiTenantWorkload} workload generator and handled by the
 * {@link com.lmax.disruptor.WorkHandler} for eg {@link TenantOperationWorkHandler}
 */
public class TenantOperationFactory {

  private static class TenantView {
    private final String tenantId;
    private final String viewName;

    public TenantView(String tenantId, String viewName) {
      this.tenantId = tenantId;
      this.viewName = viewName;
    }

    public String getTenantId() {
      return tenantId;
    }

    public String getViewName() {
      return viewName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      TenantView that = (TenantView) o;
      return getTenantId().equals(that.getTenantId()) && getViewName().equals(that.getViewName());
    }

    @Override
    public int hashCode() {
      return Objects.hash(getTenantId(), getViewName());
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(TenantOperationFactory.class);
  private final PhoenixUtil phoenixUtil;
  private final DataModel model;
  private final Scenario scenario;
  private final XMLConfigParser parser;

  private final RulesApplier rulesApplier;
  private final LoadProfile loadProfile;
  private final List<Operation> operationList = Lists.newArrayList();
  private final Map<Operation.OperationType,
    Supplier<Function<TenantOperationInfo, OperationStats>>> operationSuppliers =
      Maps.newEnumMap(Operation.OperationType.class);

  private final BloomFilter<TenantView> tenantsLoaded;
  private final ReentrantReadWriteLock viewCreationLock = new ReentrantReadWriteLock();

  public TenantOperationFactory(PhoenixUtil phoenixUtil, DataModel model, Scenario scenario) {
    this.phoenixUtil = phoenixUtil;
    this.model = model;
    this.scenario = scenario;
    this.parser = null;
    this.rulesApplier = new RulesApplier(model);
    this.loadProfile = this.scenario.getLoadProfile();
    this.tenantsLoaded = createTenantsLoadedFilter(loadProfile);

    // Read the scenario definition and load the various operations.
    // Case : Operation.OperationType.PRE_RUN
    if (scenario.getPreScenarioDdls() != null && scenario.getPreScenarioDdls().size() > 0) {
      operationSuppliers.put(Operation.OperationType.PRE_RUN,
        new PreScenarioOperationSupplier(phoenixUtil, model, scenario));
    }

    // Case : Operation.OperationType.UPSERT
    List<Operation> upsertOperations = getUpsertOperationsForScenario(scenario);
    if (upsertOperations.size() > 0) {
      operationList.addAll(upsertOperations);
      operationSuppliers.put(Operation.OperationType.UPSERT,
        new UpsertOperationSupplier(phoenixUtil, model, scenario));
    }

    // Case : Operation.OperationType.SELECT
    List<Operation> queryOperations = getQueryOperationsForScenario(scenario);
    if (queryOperations.size() > 0) {
      operationList.addAll(queryOperations);
      operationSuppliers.put(Operation.OperationType.SELECT,
        new QueryOperationSupplier(phoenixUtil, model, scenario));
    }

    // Case : Operation.OperationType.IDLE_TIME
    List<Operation> idleOperations = getIdleTimeOperationsForScenario(scenario);
    if (idleOperations.size() > 0) {
      operationList.addAll(idleOperations);
      operationSuppliers.put(Operation.OperationType.IDLE_TIME,
        new IdleTimeOperationSupplier(phoenixUtil, model, scenario));
    }

    // Case : Operation.OperationType.USER_DEFINED
    List<Operation> udfOperations = getUDFOperationsForScenario(scenario);
    if (udfOperations.size() > 0) {
      operationList.addAll(udfOperations);
      operationSuppliers.put(Operation.OperationType.USER_DEFINED,
        new UserDefinedOperationSupplier(phoenixUtil, model, scenario));
    }
  }

  private BloomFilter createTenantsLoadedFilter(LoadProfile loadProfile) {
    Funnel<TenantView> tenantViewFunnel = new Funnel<TenantView>() {
      @Override
      public void funnel(TenantView tenantView, PrimitiveSink into) {
        into.putString(tenantView.getTenantId(), Charsets.UTF_8).putString(tenantView.getViewName(),
          Charsets.UTF_8);
      }
    };

    int numTenants = 0;
    for (TenantGroup tg : loadProfile.getTenantDistribution()) {
      numTenants += tg.getNumTenants();
    }

    // This holds the info whether the tenant view was created (initialized) or not.
    return BloomFilter.create(tenantViewFunnel, numTenants, 0.0000001);
  }

  private List<Operation> getUpsertOperationsForScenario(Scenario scenario) {
    List<Operation> opList = Lists.newArrayList();
    for (final Upsert upsert : scenario.getUpserts()) {
      final Operation upsertOp = new UpsertOperation() {
        @Override
        public Upsert getUpsert() {
          return upsert;
        }

        @Override
        public String getId() {
          return upsert.getId();
        }

        @Override
        public OperationType getType() {
          return OperationType.UPSERT;
        }
      };
      opList.add(upsertOp);
    }
    return opList;
  }

  private List<Operation> getQueryOperationsForScenario(Scenario scenario) {
    List<Operation> opList = Lists.newArrayList();
    for (final QuerySet querySet : scenario.getQuerySet()) {
      for (final Query query : querySet.getQuery()) {
        Operation queryOp = new QueryOperation() {
          @Override
          public Query getQuery() {
            return query;
          }

          @Override
          public String getId() {
            return query.getId();
          }

          @Override
          public OperationType getType() {
            return OperationType.SELECT;
          }
        };
        opList.add(queryOp);
      }
    }
    return opList;
  }

  private List<Operation> getIdleTimeOperationsForScenario(Scenario scenario) {
    List<Operation> opList = Lists.newArrayList();
    for (final IdleTime idleTime : scenario.getIdleTimes()) {
      Operation idleTimeOperation = new IdleTimeOperation() {
        @Override
        public IdleTime getIdleTime() {
          return idleTime;
        }

        @Override
        public String getId() {
          return idleTime.getId();
        }

        @Override
        public OperationType getType() {
          return OperationType.IDLE_TIME;
        }
      };
      opList.add(idleTimeOperation);
    }
    return opList;
  }

  private List<Operation> getUDFOperationsForScenario(Scenario scenario) {
    List<Operation> opList = Lists.newArrayList();
    for (final UserDefined udf : scenario.getUdfs()) {
      Operation udfOperation = new UserDefinedOperation() {
        @Override
        public UserDefined getUserFunction() {
          return udf;
        }

        @Override
        public String getId() {
          return udf.getId();
        }

        @Override
        public OperationType getType() {
          return OperationType.USER_DEFINED;
        }
      };
      opList.add(udfOperation);
    }
    return opList;
  }

  public PhoenixUtil getPhoenixUtil() {
    return phoenixUtil;
  }

  public DataModel getModel() {
    return model;
  }

  public Scenario getScenario() {
    return scenario;
  }

  public List<Operation> getOperations() {
    return operationList;
  }

  public void initializeTenant(TenantOperationInfo input) throws Exception {
    TenantView tenantView = new TenantView(input.getTenantId(), scenario.getTableName());

    // Check if pre run ddls are needed.
    viewCreationLock.writeLock().lock();
    try {
      if (!tenantsLoaded.mightContain(tenantView)) {
        executePreRunOpsForTenant(tenantView, input);
        boolean updated = tenantsLoaded.put(tenantView);
        if (updated) {
          LOGGER.info(String.format("Successfully initialized tenant. [%s, %s] ",
            tenantView.tenantId, tenantView.viewName));
        }
      }
    } finally {
      viewCreationLock.writeLock().unlock();
    }
  }

  public Supplier<Function<TenantOperationInfo, OperationStats>>
    getOperationSupplier(final TenantOperationInfo input) throws Exception {

    Supplier<Function<TenantOperationInfo, OperationStats>> opSupplier =
      operationSuppliers.get(input.getOperation().getType());
    if (opSupplier == null) {
      throw new IllegalArgumentException("Unknown operation type");
    }
    return opSupplier;
  }

  private void executePreRunOpsForTenant(TenantView tenantView, TenantOperationInfo input)
    throws Exception {

    Supplier<Function<TenantOperationInfo, OperationStats>> preRunOpSupplier =
      operationSuppliers.get(Operation.OperationType.PRE_RUN);
    // Check if the scenario has a PRE_RUN operation.
    if (preRunOpSupplier != null) {
      // Initialize the tenant using the pre scenario ddls.
      final PreScenarioOperation operation = new PreScenarioOperation() {
        @Override
        public List<Ddl> getPreScenarioDdls() {
          List<Ddl> ddls = scenario.getPreScenarioDdls();
          return ddls == null ? Lists.<Ddl> newArrayList() : ddls;
        }

        @Override
        public String getId() {
          return OperationType.PRE_RUN.name();
        }

        @Override
        public OperationType getType() {
          return OperationType.PRE_RUN;
        }
      };
      // Initialize with the pre run operation.
      TenantOperationInfo preRunSample = new TenantOperationInfo(input.getModelName(),
        input.getScenarioName(), input.getTableName(), input.getTenantGroupId(),
        Operation.OperationType.PRE_RUN.name(), input.getTenantId(), operation);

      try {
        // Run the initialization operation.
        OperationStats stats = preRunOpSupplier.get().apply(preRunSample);
        LOGGER.info(phoenixUtil.getGSON().toJson(stats));
      } catch (Exception e) {
        LOGGER.error(String.format("Failed to initialize tenant. [%s, %s] ", tenantView.tenantId,
          tenantView.viewName), e);
        if (e.getClass().isAssignableFrom(SQLException.class)) {
          SQLException sqlException = (SQLException) e;
          if (
            SQLExceptionCode.CONCURRENT_TABLE_MUTATION.getErrorCode() != sqlException.getErrorCode()
          ) {
            throw e;
          }
        } else {
          throw e;
        }
      }
    }
  }

}

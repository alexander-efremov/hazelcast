/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.sql.optimizer.logical;

import com.hazelcast.sql.impl.calcite.ExpressionConverterRexVisitor;
import com.hazelcast.sql.impl.calcite.OptimizerContext;
import com.hazelcast.sql.impl.calcite.logical.rel.LogicalRel;
import com.hazelcast.sql.impl.calcite.logical.rel.MapScanLogicalRel;
import com.hazelcast.sql.impl.calcite.logical.rel.ProjectLogicalRel;
import com.hazelcast.sql.impl.calcite.logical.rel.RootLogicalRel;
import com.hazelcast.sql.impl.calcite.schema.HazelcastSchema;
import com.hazelcast.sql.impl.calcite.schema.HazelcastTable;
import com.hazelcast.sql.impl.calcite.statistics.TableStatistics;
import com.hazelcast.sql.impl.expression.CallOperator;
import com.hazelcast.sql.impl.expression.ColumnExpression;
import com.hazelcast.sql.impl.expression.ConstantExpression;
import com.hazelcast.sql.impl.expression.Expression;
import com.hazelcast.sql.impl.expression.math.PlusMinusFunction;
import com.hazelcast.sql.impl.expression.predicate.ComparisonPredicate;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.QuickTest;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlNode;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static junit.framework.TestCase.assertEquals;

@RunWith(HazelcastSerialClassRunner.class)
@Category({QuickTest.class, ParallelJVMTest.class})
public class LogicalOptimizationTest {
    /** Detailed result of the last call. */
    @SuppressWarnings("unused")
    private LastCall last;

    @After
    public void after() {
        last = null;
    }

    /**
     * Ensure that a simple project over scan gets inlined into the scan itself.
     */
    @Test
    public void testProjectIntoScan() {
        LogicalRel node = optimize("SELECT f1, f2 FROM p");

        RootLogicalRel root = assertRoot(node);

        assertScan(root.getInput(), list("f1", "f2"), list(0, 1), null);
    }

    @Test
    public void testProjectProjectIntoScan() {
        LogicalRel node = optimize("SELECT f2 FROM (SELECT f1, f2 FROM p)");

        RootLogicalRel root = assertRoot(node);

        assertScan(root.getInput(), list("f1", "f2"), list(1), null);
    }

    @Test
    public void testProjectProjectFilterIntoScan() {
        LogicalRel node = optimize("SELECT f2 FROM (SELECT f1, f2 FROM p WHERE f3 > 1)");

        RootLogicalRel root = assertRoot(node);

        assertScan(
            root.getInput(),
            list("f3", "f1", "f2"),
            list(2),
            new ComparisonPredicate(new ColumnExpression(0), new ConstantExpression<>(1), CallOperator.GREATER_THAN)
        );
    }

    @Test
    public void testComplexProjectFilterScan() {
        LogicalRel node = optimize("SELECT f1 + f2, f2 FROM p WHERE f3 > 1");

        RootLogicalRel root = assertRoot(node);

        ProjectLogicalRel project = assertProject(
            root.getInput(),
            list(
                new PlusMinusFunction(new ColumnExpression(0), new ColumnExpression(1), false),
                new ColumnExpression(1)
            )
        );

        assertScan(
            project.getInput(),
            list("f3", "f1", "f2"),
            list(1, 2),
            new ComparisonPredicate(new ColumnExpression(0), new ConstantExpression<>(1), CallOperator.GREATER_THAN)
        );
    }

    /**
     * Ensure that a simple project-filter-scan rule is converted to a scan.
     */
    @Test
    public void testProjectFilterIntoScan() {
        LogicalRel node = optimize("SELECT f1, f2 FROM p WHERE f3 > 1");

        RootLogicalRel root = assertRoot(node);

        assertScan(
            root.getInput(),
            list("f3", "f1", "f2"),
            list(1, 2),
            new ComparisonPredicate(
                new ColumnExpression(0),
                new ConstantExpression<>(1),
                CallOperator.GREATER_THAN
            )
        );
    }

    private static RootLogicalRel assertRoot(RelNode node) {
        return assertClass(node, RootLogicalRel.class);
    }

    private static void assertScan(RelNode node, List<String> expFields, List<Integer> expProjects, Expression expFilter) {
        MapScanLogicalRel scan = assertClass(node, MapScanLogicalRel.class);

        assertFields(expFields, scan.getTable().getRowType().getFieldNames());
        assertProjectedFields(expProjects, scan.getProjects());

        Expression filter = scan.getFilter() != null ? scan.getFilter().accept(ExpressionConverterRexVisitor.INSTANCE) : null;

        assertEquals(expFilter, filter);
    }

    private static void assertFields(List<String> expFields, List<String> fields) {
        if (fields == null) {
            fields = new ArrayList<>();
        } else {
            fields = new ArrayList<>(fields);
        }

        assertEquals(expFields, fields);
    }

    private static void assertProjectedFields(List<Integer> expProjects, List<Integer> projects) {
        if (projects == null) {
            projects = new ArrayList<>();
        } else {
            projects = new ArrayList<>(projects);
        }

        assertEquals(expProjects, projects);
    }

    private ProjectLogicalRel assertProject(RelNode rel, List<Expression> expProjects) {
        ProjectLogicalRel project = assertClass(rel, ProjectLogicalRel.class);

        List<Expression> projects = new ArrayList<>();

        for (RexNode projectExpr : project.getProjects()) {
            projects.add(projectExpr.accept(ExpressionConverterRexVisitor.INSTANCE));
        }

        expProjects = expProjects != null ? new ArrayList<>(expProjects) : new ArrayList<>();

        assertEquals(expProjects, projects);

        return project;
    }

    @SuppressWarnings("unchecked")
    private static <T> T assertClass(RelNode rel, Class<? extends LogicalRel> expClass) {
        assertEquals(expClass, rel.getClass());

        return (T)rel;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(T... vals) {
        if (vals == null) {
            return new ArrayList<>();
        } else {
            return new ArrayList<>(Arrays.asList(vals));
        }
    }

    private LogicalRel optimize(String sql) {
        OptimizerContext context = createContext();

        SqlNode node = context.parse(sql);
        RelNode converted = context.convert(node);
        LogicalRel logical = context.optimizeLogical(converted);

        last = new LastCall(node, converted, logical);

        return logical;
    }

    private static OptimizerContext createContext() {
        Map<String, Table> tableMap = new HashMap<>();
        tableMap.put("p", new HazelcastTable("p", true, null, new TableStatistics(100)));

        HazelcastSchema rootSchema = new HazelcastSchema(tableMap);

        return OptimizerContext.create(rootSchema, 1);
    }

    /**
     * Result of the last call to optimizer. Not used directly. You may look at it in the debugger if needed.
     */
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private static class LastCall {
        /** SQL node. */
        private final SqlNode node;

        /** Original rel. */
        private final RelNode converted;

        /** Optimized logical rel. */
        private final LogicalRel logical;

        public LastCall(SqlNode node, RelNode converted, LogicalRel logical) {
            this.node = node;
            this.converted = converted;
            this.logical = logical;
        }
    }
}
package org.apache.pig.backend.hadoop.executionengine.tez;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pig.PigException;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.expressionOperators.POProject;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POForEach;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POPackage;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POStore;
import org.apache.pig.data.DataType;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.plan.NodeIdGenerator;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.plan.PlanException;

import com.google.common.collect.Lists;

public class TezCompilerUtil {

    private TezCompilerUtil() {
    }

    // simpleConnectTwoVertex is a utility to end a vertex equivalent to map and start vertex equivalent to
    // reduce in a tez operator:
    // 1. op1 is open
    // 2. op2 is blank
    //    POPackage to start a reduce vertex
    // 3. POLocalRearrange/POPackage are trivial
    // 4. User need to connect op1 to op2 themselves
    static public void simpleConnectTwoVertex(TezOperPlan tezPlan, TezOperator op1, TezOperator op2, String scope, NodeIdGenerator nig) throws PlanException
    {
        PhysicalPlan ep = new PhysicalPlan();
        POProject prjStar = new POProject(new OperatorKey(scope,nig.getNextNodeId(scope)));
        prjStar.setResultType(DataType.TUPLE);
        prjStar.setStar(true);
        ep.add(prjStar);

        List<PhysicalPlan> eps = new ArrayList<PhysicalPlan>();
        eps.add(ep);

        POLocalRearrangeTez lr = new POLocalRearrangeTez(new OperatorKey(scope,nig.getNextNodeId(scope)));
        try {
            lr.setIndex(0);
        } catch (ExecException e) {
            int errCode = 2058;
            String msg = "Unable to set index on the newly created POLocalRearrange.";
            throw new PlanException(msg, errCode, PigException.BUG, e);
        }
        lr.setKeyType(DataType.TUPLE);
        lr.setPlans(eps);
        lr.setResultType(DataType.TUPLE);
        lr.setOutputKey(op2.getOperatorKey().toString());

        op1.plan.addAsLeaf(lr);

        POPackage pkg = new POPackage(new OperatorKey(scope,nig.getNextNodeId(scope)));
        pkg.getPkgr().setKeyType(DataType.TUPLE);
        pkg.setNumInps(1);
        boolean[] inner = {false};
        pkg.getPkgr().setInner(inner);
        op2.plan.add(pkg);

        op2.plan.addAsLeaf(getForEachPlain(scope, nig));

        connect(tezPlan, op1, op2);
    }

    static public TezEdgeDescriptor connect(TezOperPlan plan, TezOperator from, TezOperator to) throws PlanException {
        plan.connect(from, to);
        // Add edge descriptors to old and new operators
        TezEdgeDescriptor edge = new TezEdgeDescriptor();
        to.inEdges.put(from.getOperatorKey(), edge);
        from.outEdges.put(to.getOperatorKey(), edge);
        return edge;
    }

    static public POForEach getForEach(POProject project, int rp, String scope, NodeIdGenerator nig) {
        PhysicalPlan forEachPlan = new PhysicalPlan();
        forEachPlan.add(project);

        List<PhysicalPlan> forEachPlans = Lists.newArrayList();
        forEachPlans.add(forEachPlan);

        List<Boolean> flatten = Lists.newArrayList();
        flatten.add(true);

        POForEach forEach = new POForEach(new OperatorKey(scope, nig.getNextNodeId(scope)), rp, forEachPlans, flatten);
        forEach.setResultType(DataType.BAG);
        return forEach;
    }

    // Get a plain POForEach: ForEach X generate flatten($1)
    static public POForEach getForEachPlain(String scope, NodeIdGenerator nig) {
        POProject project = new POProject(new OperatorKey(scope, nig.getNextNodeId(scope)));
        project.setResultType(DataType.TUPLE);
        project.setStar(false);
        project.setColumn(1);
        project.setOverloaded(true);
        return getForEach(project, -1, scope, nig);
    }

    static public POStore getStore(String scope, NodeIdGenerator nig) {
        POStore st = new POStoreTez(new OperatorKey(scope, nig.getNextNodeId(scope)));
        // mark store as tmp store. These could be removed by the
        // optimizer, because it wasn't the user requesting it.
        st.setIsTmpStore(true);
        return st;
    }

    static public void setCustomPartitioner(String customPartitioner, TezOperator tezOp) throws IOException {
        if (customPartitioner != null) {
            for (TezEdgeDescriptor edge : tezOp.inEdges.values()) {
                edge.partitionerClass = PigContext.resolveClassName(customPartitioner);
            }
        }
    }
}
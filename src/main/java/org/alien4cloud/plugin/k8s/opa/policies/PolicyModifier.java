package org.alien4cloud.plugin.k8s.opa.policies;

import alien4cloud.tosca.context.ToscaContextual;
import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.model.templates.Topology;
import org.springframework.stereotype.Component;


@Component("k8s-opa-policy-modifier")
public class PolicyModifier extends TopologyModifierSupport {

    public final static String OPA_POLICY = "org.alien4cloud.policies.OPAPolicy";

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        // DO NOTHING
    }
}

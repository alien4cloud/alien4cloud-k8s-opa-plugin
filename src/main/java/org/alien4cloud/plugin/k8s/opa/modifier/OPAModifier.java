package org.alien4cloud.plugin.k8s.opa.modifier;

import alien4cloud.paas.wf.validation.WorkflowValidator;
import alien4cloud.tosca.context.ToscaContextual;
import alien4cloud.utils.PropertyUtil;

import org.alien4cloud.alm.deployment.configuration.flow.FlowExecutionContext;
import org.alien4cloud.alm.deployment.configuration.flow.TopologyModifierSupport;
import org.alien4cloud.tosca.model.CSARDependency;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.templates.PolicyTemplate;
import org.alien4cloud.tosca.model.templates.Topology;
import org.alien4cloud.tosca.normative.constants.NormativeRelationshipConstants;
import org.alien4cloud.tosca.utils.TopologyNavigationUtil;

import static org.alien4cloud.plugin.kubernetes.csar.Version.K8S_CSAR_VERSION;
import static org.alien4cloud.plugin.kubernetes.modifier.KubernetesAdapterModifier.A4C_KUBERNETES_ADAPTER_MODIFIER_TAG_REPLACEMENT_NODE_FOR;
import static org.alien4cloud.plugin.kubernetes.modifier.KubernetesAdapterModifier.K8S_TYPES_KUBE_CLUSTER;
import static org.alien4cloud.plugin.kubernetes.modifier.KubernetesAdapterModifier.NAMESPACE_RESOURCE_NAME;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_DEPLOYMENT_RESOURCE;
import static org.alien4cloud.plugin.kubernetes.modifier.KubeTopologyUtils.K8S_TYPES_SIMPLE_RESOURCE;

import static org.alien4cloud.plugin.k8s.opa.policies.PolicyModifier.OPA_POLICY;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.commons.lang.StringUtils;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component("opa-modifier")
public class OPAModifier extends TopologyModifierSupport {

    @Inject
    private OPAConfiguration conf;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    @ToscaContextual
    public void process(Topology topology, FlowExecutionContext context) {
        log.info("Processing topology " + topology.getId());

        try {
            WorkflowValidator.disableValidationThreadLocal.set(true);
            doProcess(topology, context);
        } catch (Exception e) {
            log.warn("Couldn't process OPAModifier modifier, got " + e.getMessage());
        } finally {
            WorkflowValidator.disableValidationThreadLocal.remove();
        }
    }

    private void doProcess(Topology topology, FlowExecutionContext context) {
        /* get namespace */
        String namespace = null;
        NodeTemplate kubeNS = topology.getNodeTemplates().get((String)context.getExecutionCache().get(NAMESPACE_RESOURCE_NAME));
        if (kubeNS != null) {
           try {
              ObjectNode spec = (ObjectNode) mapper.readTree(PropertyUtil.getScalarValue(kubeNS.getProperties().get("resource_spec")));
              namespace = spec.with("metadata").get("name").textValue();
           } catch(Exception e) {
              log.warn("Can not get namespace: {}", e.getMessage());
           }
        }
        if (StringUtils.isBlank(namespace)) {
           log.info ("No namespace, can not perform");
           return;
        }

        /* get kube config */
        String kube_config = (String) context.getExecutionCache().get(K8S_TYPES_KUBE_CLUSTER);

        /* get initial topology */
        Topology init_topology = (Topology)context.getExecutionCache().get(FlowExecutionContext.INITIAL_TOPOLOGY);

        /* get deployment nodes */
        Set<NodeTemplate> k8sNodes =  TopologyNavigationUtil.getNodesOfType(topology, K8S_TYPES_DEPLOYMENT_RESOURCE, true);

        /* deployment nodes which have containers with OPA policy (key is opa type) */
        Map<String,Set<NodeTemplate>> targetDeployments = new HashMap<String,Set<NodeTemplate>>();
        /* get all OPA policies targets on initial topology */
        Set<PolicyTemplate> policies = TopologyNavigationUtil.getPoliciesOfType(init_topology, OPA_POLICY, true);
        for (PolicyTemplate policy : policies) {
           /* get all target nodes on current policy */
           Set<NodeTemplate> targetNodes = TopologyNavigationUtil.getTargetedMembers(init_topology, policy);
           String opatype = PropertyUtil.getScalarValue(policy.getProperties().get("opa_type"));
           if (targetDeployments.get(opatype) == null) {
              targetDeployments.put (opatype, new HashSet<NodeTemplate>());
           }
           /* get deployment nodes corresponding to target nodes */
           for (NodeTemplate targetNode : targetNodes) {
              NodeTemplate deployment = TopologyNavigationUtil.getImmediateHostTemplate(init_topology, targetNode);
              targetDeployments.get(opatype).add(deployment);
           }
        }

        /* add OPA containers to deployment nodes which have at least one container with OPA policy, for each OPA type */
        int index = 0;
        for (String opatype : targetDeployments.keySet()) {
           boolean modified = false;
           index++;
           /* deployment nodes which have an OPA container added by this plugin */
           Set<NodeTemplate> modifiedDeployments = new HashSet<NodeTemplate>();
           for (NodeTemplate targetDeployment : targetDeployments.get(opatype)) {
              modified |= processNode(topology, targetDeployment, k8sNodes, opatype, modifiedDeployments, index);
           }
           if (modified) {
              addConfigMap(topology, kube_config, namespace, kubeNS.getName(), modifiedDeployments, opatype, index);
           }
        }

    }

    /**
     * add an OPA container to node if possible
     * topology: K8S processed topology
     * node: KubeDeployment node
     * kubeNodes: K8S deployment nodes
     * opatype: policy property
     * modifiedDeployments: list of K8S deployments modified for cuurent OPA
     * index: OPA index
     *
     * returns true if OPA container has been added, else false
     * 
     **/
    private boolean processNode(Topology topology, NodeTemplate node, Set<NodeTemplate> kubeNodes, String opatype,
                                Set<NodeTemplate> modifiedDeployments, int index) {
        /* look for k8s node corresponding to deployment */
        NodeTemplate kubeNode = null;
        for (NodeTemplate knode : kubeNodes) {
           String initialNodeName  = TopologyModifierSupport.getNodeTagValueOrNull(knode, A4C_KUBERNETES_ADAPTER_MODIFIER_TAG_REPLACEMENT_NODE_FOR);
           if ( (initialNodeName != null) && initialNodeName.equals(node.getName()) ) { 
              kubeNode = knode;
              break;
           }
        }
        if (kubeNode == null) {
           log.warn("Can not find K8S node for {}", node.getName());
           return false;
        }

        /* get resource spec */
        JsonNode spec = null;
        try {
           spec = mapper.readTree(PropertyUtil.getScalarValue(kubeNode.getProperties().get("resource_spec")));
        } catch(Exception e) {
           log.error("Can't get node {} spec: {}", kubeNode.getName(), e.getMessage());
           return false;
        }
        /* create OPA container and add it to JSON containers array */
        ArrayNode containers = (ArrayNode)spec.with("spec").with("template").with("spec").withArray("containers");
        ObjectNode opaContainer = mapper.createObjectNode();
        opaContainer.put ("name", "opa-container" + index);
        opaContainer.put ("image", conf.getImage());
        ArrayNode ports = mapper.createArrayNode();
        ObjectNode port1 = mapper.createObjectNode();
        port1.put ("name", "http");
        port1.put ("containerPort", 8181);
        ports.add (port1);
        ObjectNode port2 = mapper.createObjectNode();
        port2.put ("name", "http-metric");
        port2.put ("containerPort", 8183);
        ports.add (port2);
        opaContainer.put("ports", ports);
        ArrayNode args = mapper.createArrayNode();
        args.add ("run");
        args.add ("--ignore=.*");
        args.add ("--server");
        args.add ("--addr");
        args.add ("0.0.0.0:8181");
        args.add ("--diagnostic-addr");
        args.add ("0.0.0.0:8183");
        args.add ("--skip-version-check");
        args.add ("--config-file");
        args.add ("/etc/opa/config.yaml");
        opaContainer.put("args", args);
        ArrayNode volumeMounts = mapper.createArrayNode();
        ObjectNode volumeMount = mapper.createObjectNode();
        volumeMount.put("readOnly", true);
        volumeMount.put("mountPath", "/etc/opa/config.yaml");
        volumeMount.put("subPath", "config.yaml");
        volumeMount.put("name", "opa-volume" + index);
        volumeMounts.add(volumeMount);
        opaContainer.put("volumeMounts", volumeMounts);
        ArrayNode envs = mapper.createArrayNode();
        ObjectNode env = mapper.createObjectNode();
        env.put ("name", "OPA_TYPE");
        env.put ("value", opatype);
        envs.add(env);
        opaContainer.put("env", envs);
        containers.add(opaContainer);

        /* create volume and add it to deployment volumes array (create volumes array if required) */
        ObjectNode volume = mapper.createObjectNode();
        volume.put ("name", "opa-volume" + index);
        ObjectNode configMap = mapper.createObjectNode();
        configMap.put("name", "opa-config" + index);
        volume.put ("configMap", configMap);
        ((ArrayNode)spec.with("spec").with("template").with("spec").withArray("volumes")).add(volume);

        String specStr = null;
        try {
           specStr = mapper.writeValueAsString(spec);
        } catch(Exception e) {
           log.error("Can't rewrite node {} spec: {}", kubeNode.getName(), e.getMessage());
           return false;
        }
        setNodePropertyPathValue(null, topology, kubeNode, "resource_spec", new ScalarPropertyValue(specStr));

        modifiedDeployments.add(kubeNode);

        return true;

    }

    /**
      * adds OPA configmap to topology
      *
      * topology: K8S processed topology
      * kube_config: K8S config
      * namespace: K8S namespace
      * nsNodeName: K8S namespace node name in topology
      * deployNodes: deployment nodes to be related to config map
      * opatype: policy property
      * index: OPA index
      **/
    private void addConfigMap(Topology topology, String kube_config, String namespace, String nsNodeName, 
                               Set<NodeTemplate> deployNodes, String opatype, int index) {
       String resource_spec = "apiVersion: v1\n" +
                              "kind: ConfigMap\n" +
                              "metadata:\n" +
                              "  name: opa-config" + index + "\n" +
                              "  labels:\n" +
                              "    a4c_id: opa-config" + index + "\n" +
                              "data:\n" +
                              "  config.yaml: |\n" +
                              "    services:\n" +
                              "      - name: " + conf.getService_name() + " \n" +
                              "        url: " + conf.getService_url() + "  \n" +
                              "        allow_insecure_tls: " + conf.isService_insecure_tls() + " \n" +
                              "    discovery:\n" +
                              "      service: " + conf.getDiscovery_service() + " \n" +
                              "      resource: " + conf.getDiscovery_resource() + " \n" +
                              "      decision: " + conf.getDiscovery_decision() + " \n" +
                              "      polling:\n" +
                              "        min_delay_seconds: " + conf.getDiscovery_polling_min_delay() + "\n" +
                              "        max_delay_seconds: " + conf.getDiscovery_polling_max_delay() + "\n" +
                              "    labels:\n" +
                              "      opa_type: " + opatype +"\n";

        NodeTemplate resource = addNodeTemplate(null, topology, "OpaConfig" + index, K8S_TYPES_SIMPLE_RESOURCE, getK8SCsarVersion(topology));

        setNodePropertyPathValue(null,topology,resource,"resource_type", new ScalarPropertyValue("configmap"));
        setNodePropertyPathValue(null,topology,resource,"resource_id", new ScalarPropertyValue("opa-config" + index));
        setNodePropertyPathValue(null,topology,resource,"resource_spec", new ScalarPropertyValue(resource_spec));
        setNodePropertyPathValue(null, topology, resource, "kube_config", new ScalarPropertyValue(kube_config));
        if (!StringUtils.isBlank(namespace)) {
           setNodePropertyPathValue(null,topology,resource,"namespace", new ScalarPropertyValue(namespace));
           addRelationshipTemplate(null,topology, resource, nsNodeName, NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
        }
        deployNodes.forEach (node -> {
           addRelationshipTemplate(null,topology, node, "OpaConfig" + index, NormativeRelationshipConstants.DEPENDS_ON, "dependency", "feature");
        });
    }

    private static String getK8SCsarVersion(Topology topology) {
        for (CSARDependency dep : topology.getDependencies()) {
            if (dep.getName().equals("org.alien4cloud.kubernetes.api")) {
                return dep.getVersion();
            }
        }
        return K8S_CSAR_VERSION;
    }
}

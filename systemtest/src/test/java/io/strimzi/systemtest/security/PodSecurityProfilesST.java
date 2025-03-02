/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.security;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.strimzi.api.kafka.model.KafkaBridge;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaMirrorMaker;
import io.strimzi.api.kafka.model.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.systemtest.AbstractST;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.annotations.KindIPv6NotSupported;
import io.strimzi.systemtest.annotations.ParallelNamespaceTest;
import io.strimzi.systemtest.annotations.RequiredMinKubeOrOcpBasedKubeVersion;
import io.strimzi.systemtest.enums.PodSecurityProfile;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClients;
import io.strimzi.systemtest.kafkaclients.internalClients.KafkaClientsBuilder;
import io.strimzi.systemtest.storage.TestStorage;
import io.strimzi.systemtest.templates.crd.KafkaBridgeTemplates;
import io.strimzi.systemtest.templates.crd.KafkaConnectTemplates;
import io.strimzi.systemtest.templates.crd.KafkaConnectorTemplates;
import io.strimzi.systemtest.templates.crd.KafkaMirrorMaker2Templates;
import io.strimzi.systemtest.templates.crd.KafkaMirrorMakerTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTemplates;
import io.strimzi.systemtest.templates.crd.KafkaTopicTemplates;
import io.strimzi.systemtest.utils.ClientUtils;
import io.strimzi.systemtest.utils.kafkaUtils.KafkaConnectUtils;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.POD_SECURITY_PROFILES_RESTRICTED;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 *
 * PodSecurityProfilesST provides tests for Pod Security profiles. In short, Pod security profiles are a mechanism used
 * in Pods or containers, which may prohibit some set of operations (e.g., running only as a non-root user, allowing
 * only some Volume types etc.).
 *
 * Test cases are design to verify common behaviour of Pod Security profiles. Specifically, (i.) we check if containers such
 * as Kafka, ZooKeeper, Entity Operator, KafkaBridge has properly set .securityContext (ii.) then we check if these
 * resources working and are stable with exchanging messages.
 */
@Tag(REGRESSION)
@Tag(POD_SECURITY_PROFILES_RESTRICTED)
public class PodSecurityProfilesST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(PodSecurityProfilesST.class);

    /**
     * @description This test case verifies common behaviour of Pod Security profiles.
     *
     * @steps
     *  1. - Add restricted security profile to the namespace containing all resources, by applying according label
     *     - Namespace is modified
     *  2. - Deploy 3 Kafka Clusters, of which 2 will serve as targets and one as a source and for other purposes
     *     - Kafka clusters are deployed
     *  3. - Deploy all additional Operands which are to be tested, i.e., KafkaMirrorMaker KafkaMirrorMaker2 KafkaBridge KafkaConnect, KafkaConnector.
     *     - All components are deployed targeting respective Kafka Clusters
     *  4. - Deploy producer which will produce data into Topic residing in Kafka Cluster serving as Source for KafkaMirrorMakers and is targeted by other Operands
     *     - Messages are sent into KafkaTopic
     *  5. - Verify that containers such as Kafka, ZooKeeper, Entity Operator, KafkaBridge has properly set .securityContext
     *     - All containers and Pods have expected properties
     *  6. - Verify KafkaConnect and KafkaConnector are working by checking presence of Data in file targeted by FileSink KafkaConnector
     *     - Data are present here
     *  7. - Verify Kafka and MirrorMakers by Deploy kafka Consumers in respective target Kafka Clusters targeting KafkaTopics created by mirroring
     *     - Data are present here
     *  8. - Deploy Kafka consumer with invalid configuration regarding, i.e., without pod security profile
     *     - Consumer is unable to communicate correctly with Kafka
     *
     * @usecase
     *  - security-profiles
     */
    @Tag(ACCEPTANCE)
    @KindIPv6NotSupported("Kafka Connect Build using the Kaniko builder is not available under the restricted security profile")
    @ParallelNamespaceTest
    @RequiredMinKubeOrOcpBasedKubeVersion(kubeVersion = 1.23, ocpBasedKubeVersion = 1.24)
    void testOperandsWithRestrictedSecurityProfile(ExtensionContext extensionContext) {

        final TestStorage testStorage = new TestStorage(extensionContext);

        final String mm1TargetClusterName = testStorage.getTargetClusterName() + "-mm1";
        final String mm2TargetClusterName = testStorage.getTargetClusterName() + "-mm2";
        final String mm2SourceMirroredTopicName = testStorage.getClusterName() + "." + testStorage.getTopicName();
        final int messageCount = 100;

        addRestrictedPodSecurityProfileToNamespace(testStorage.getNamespaceName());

        // 1 source Kafka Cluster, 2 target Kafka Cluster, 1 for MM1 and MM2 each having different target Kafka Cluster,

        LOGGER.info("Deploy Kafka Clusters resources");
        resourceManager.createResourceWithWait(extensionContext,
            KafkaTemplates.kafkaEphemeral(testStorage.getClusterName(), 1).build(),
            KafkaTemplates.kafkaEphemeral(mm1TargetClusterName, 1).build(),
            KafkaTemplates.kafkaEphemeral(mm2TargetClusterName, 1).build(),
            KafkaTopicTemplates.topic(testStorage).build()
        );

        // Kafka Bridge and KafkaConnect use main Kafka Cluster (one serving as source for MM1 and MM2)
        // MM1 and MM2 shares source Kafka Cluster and each have their own target kafka cluster

        LOGGER.info("Deploy all additional operands: MM1, MM2, Bridge, KafkaConnect");
        resourceManager.createResourceWithWait(extensionContext,
            KafkaConnectTemplates.kafkaConnectWithFilePlugin(testStorage.getClusterName(), testStorage.getNamespaceName(), 1)
                .editMetadata()
                    .addToAnnotations(Annotations.STRIMZI_IO_USE_CONNECTOR_RESOURCES, "true")
                .endMetadata()
                .editSpec()
                    .addToConfig("key.converter.schemas.enable", false)
                    .addToConfig("value.converter.schemas.enable", false)
                    .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                    .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .endSpec()
                .build(),
            KafkaBridgeTemplates.kafkaBridge(testStorage.getClusterName(), KafkaResources.plainBootstrapAddress(testStorage.getClusterName()), 1).build(),
            KafkaMirrorMakerTemplates.kafkaMirrorMaker(testStorage.getClusterName() + "-mm1", testStorage.getClusterName(), mm1TargetClusterName, ClientUtils.generateRandomConsumerGroup(), 1, false)
                .build(),
            KafkaMirrorMaker2Templates.kafkaMirrorMaker2(testStorage.getClusterName() + "-mm2", mm2TargetClusterName, testStorage.getClusterName(), 1, false)
                .editSpec()
                    .editFirstMirror()
                        .editSourceConnector()
                            .addToConfig("refresh.topics.interval.seconds", "1")
                        .endSourceConnector()
                    .endMirror()
                .endSpec()
                .build());

        LOGGER.info("Deploy File Sink Kafka Connector: {}/{}", testStorage.getNamespaceName(), testStorage.getClusterName());
        resourceManager.createResourceWithWait(extensionContext, KafkaConnectorTemplates.kafkaConnector(testStorage.getClusterName())
            .editSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
                .addToConfig("topics", testStorage.getTopicName())
                .addToConfig("file", Constants.DEFAULT_SINK_FILE_PATH)
                .addToConfig("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .addToConfig("value.converter", "org.apache.kafka.connect.storage.StringConverter")
            .endSpec()
            .build());

        // Messages produced to Main Kafka Cluster (source) will be sinked to file, and mirrored into targeted Kafkas to later verify Operands work correctly.
        LOGGER.info("Deploy producer: {} and produce {} messages into Kafka: {} in Ns: ", testStorage.getProducerName(), testStorage.getClusterName(), messageCount);
        final KafkaClients kafkaClients = new KafkaClientsBuilder()
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(messageCount)
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(testStorage.getClusterName()))
            .withProducerName(testStorage.getProducerName())
            .withConsumerName(testStorage.getConsumerName())
            .withNamespaceName(testStorage.getNamespaceName())
            .withUsername(testStorage.getUsername())
            .withPodSecurityPolicy(PodSecurityProfile.RESTRICTED)
            .build();
        resourceManager.createResourceWithWait(extensionContext, kafkaClients.producerStrimzi());
        ClientUtils.waitForProducerClientSuccess(testStorage);

        // verifies that Pods and Containers have proper generated SC
        final List<Pod> podsWithProperlyGeneratedSecurityCOntexts = PodUtils.getKafkaClusterPods(testStorage);
        podsWithProperlyGeneratedSecurityCOntexts.addAll(kubeClient().listPods(testStorage.getNamespaceName(), testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaBridge.RESOURCE_KIND));
        podsWithProperlyGeneratedSecurityCOntexts.addAll(kubeClient().listPods(testStorage.getNamespaceName(), testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND));
        podsWithProperlyGeneratedSecurityCOntexts.addAll(kubeClient().listPods(testStorage.getNamespaceName(), testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaMirrorMaker2.RESOURCE_KIND));
        podsWithProperlyGeneratedSecurityCOntexts.addAll(kubeClient().listPods(testStorage.getNamespaceName(), testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaMirrorMaker.RESOURCE_KIND));
        verifyPodAndContainerSecurityContext(podsWithProperlyGeneratedSecurityCOntexts);

        LOGGER.info("Verify that Kafka cluster is usable and everything (MM1, MM2, and Connector) is working");
        verifyStabilityOfKafkaCluster(extensionContext, testStorage);

        // verify KafkaConnect
        final String kafkaConnectPodName = kubeClient(testStorage.getNamespaceName()).listPods(testStorage.getNamespaceName(), testStorage.getClusterName(), Labels.STRIMZI_KIND_LABEL, KafkaConnect.RESOURCE_KIND).get(0).getMetadata().getName();
        KafkaConnectUtils.waitForMessagesInKafkaConnectFileSink(testStorage.getNamespaceName(), kafkaConnectPodName, Constants.DEFAULT_SINK_FILE_PATH, "99");

        // verify MM1, as topic name does not change, only bootstrap server is changed.
        final KafkaClients mm1Client = new KafkaClientsBuilder(kafkaClients)
            .withConsumerName("mm1-consumer")
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(mm1TargetClusterName))
            .build();
        resourceManager.createResourceWithWait(extensionContext, mm1Client.consumerStrimzi());
        ClientUtils.waitForClientSuccess("mm1-consumer", testStorage.getNamespaceName(), messageCount);

        // verify MM2
        final KafkaClients mm2Client = new KafkaClientsBuilder(kafkaClients)
            .withTopicName(mm2SourceMirroredTopicName)
            .withConsumerName("mm2-consumer")
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(mm2TargetClusterName))
            .build();
        resourceManager.createResourceWithWait(extensionContext, mm2Client.consumerStrimzi());
        ClientUtils.waitForClientSuccess("mm2-consumer", testStorage.getNamespaceName(), messageCount);

        // verify that client incorrectly configured Pod Security Profile wont successfully communicate.
        final KafkaClients incorrectKafkaClients = new KafkaClientsBuilder(kafkaClients)
            .withPodSecurityPolicy(PodSecurityProfile.DEFAULT)
            .build();

        // excepted failure
        // job_controller.go:1437 pods "..." is forbidden: violates PodSecurity "restricted:latest": allowPrivilegeEscalation != false
        // (container "..." must set securityContext.allowPrivilegeEscalation=false),
        // unrestricted capabilities (container "..." must set securityContext.capabilities.drop=["ALL"]),
        // runAsNonRoot != true (pod or container "..." must set securityContext.runAsNonRoot=true),
        // seccompProfile (pod or container "..." must set securityContext.seccompProfile.type to "RuntimeDefault" or "Localhost")
        resourceManager.createResourceWithoutWait(extensionContext, incorrectKafkaClients.producerStrimzi());
        ClientUtils.waitForProducerClientTimeout(testStorage);
    }

    @BeforeAll
    void beforeAll(ExtensionContext extensionContext) {
        // we configure Pod Security via provider class, which sets SecurityContext to all containers (e.g., Kafka, ZooKeeper,
        // Entity Operator, Bridge). Another alternative but more complicated is to set it via .template section inside each CR.
        clusterOperator = clusterOperator
            .defaultInstallation(extensionContext)
            .withExtraEnvVars(Collections.singletonList(new EnvVarBuilder()
                .withName("STRIMZI_POD_SECURITY_PROVIDER_CLASS")
                // default is `baseline` and thus other tests suites are testing it
                .withValue("restricted")
                .build()))
            .createInstallation()
            .runInstallation();
    }

    private void verifyPodAndContainerSecurityContext(final Iterable<? extends Pod> kafkaPods) {
        for (final Pod pod : kafkaPods) {
            // verify Container SecurityContext
            if (pod.getSpec().getContainers() != null) {
                verifyContainerSecurityContext(pod.getSpec().getContainers());
            }
        }
    }

    /**
     * Provides a check for Container SecurityContext inside Pod resource.
     *
     * securityContext:
     *     allowPrivilegeEscalation: false
     *     capabilities:
     *      drop:
     *       - ALL
     *     runAsNonRoot: true
     *     seccompProfile:
     *      type: RuntimeDefault
     * @param containers    containers, with injected SecurityContext by Cluster Operator for restricted profile
     */
    private void verifyContainerSecurityContext(final Iterable<? extends Container> containers) {
        for (final Container c : containers) {
            final SecurityContext sc = c.getSecurityContext();
            LOGGER.debug("Verifying Container: {} with SecurityContext: {}", c.getName(), sc);

            assertThat(sc.getAllowPrivilegeEscalation(), CoreMatchers.is(false));
            assertThat(sc.getCapabilities().getDrop(), CoreMatchers.hasItem("ALL"));
            assertThat(sc.getRunAsNonRoot(), CoreMatchers.is(true));
            assertThat(sc.getSeccompProfile().getType(), CoreMatchers.is("RuntimeDefault"));
        }
    }

    private void verifyStabilityOfKafkaCluster(final ExtensionContext extensionContext, final TestStorage testStorage) {
        final KafkaClients kafkaClients = new KafkaClientsBuilder()
            .withTopicName(testStorage.getTopicName())
            .withMessageCount(testStorage.getMessageCount())
            .withBootstrapAddress(KafkaResources.plainBootstrapAddress(testStorage.getClusterName()))
            .withConsumerName(testStorage.getConsumerName())
            .withProducerName(testStorage.getProducerName())
            .withNamespaceName(testStorage.getNamespaceName())
            .withPodSecurityPolicy(PodSecurityProfile.RESTRICTED)
            .build();

        resourceManager.createResourceWithWait(extensionContext,
            kafkaClients.producerStrimzi(),
            kafkaClients.consumerStrimzi()
        );

        ClientUtils.waitForClientsSuccess(testStorage);
    }

    private void addRestrictedPodSecurityProfileToNamespace(final String namespaceName) {
        final Namespace namespace = kubeClient().getNamespace(namespaceName);
        final Map<String, String> namespaceLabels = namespace.getMetadata().getLabels();
        namespaceLabels.put("pod-security.kubernetes.io/enforce", "restricted");
        namespace.getMetadata().setLabels(namespaceLabels);
        kubeClient().updateNamespace(namespace);
    }
}

/*
 * Copyright Consensys Software Inc., 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.validator.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.ethereum.json.types.validator.BeaconCommitteeSelectionProof;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.validator.api.ValidatorApiChannel;

class DvtAttestationAggregationsTest {

  private final DataStructureUtil dataStructureUtil =
      new DataStructureUtil(TestSpecFactory.createDefault());
  private DvtAttestationAggregations loader;
  private ValidatorApiChannel validatorApiChannel;

  @BeforeEach
  public void setUp() {
    validatorApiChannel = mock(ValidatorApiChannel.class);
  }

  @Test
  public void completesAllFuturesWhenMiddlewareReturnsAllSelectionProofs() {
    final BeaconCommitteeSelectionProof combinedProofForValidator1 = combinedProof(1);
    final BeaconCommitteeSelectionProof combinedProofForValidator2 = combinedProof(2);
    when(validatorApiChannel.getBeaconCommitteeSelectionProof(any()))
        .thenReturn(
            SafeFuture.completedFuture(
                Optional.of(List.of(combinedProofForValidator1, combinedProofForValidator2))));

    loader = new DvtAttestationAggregations(validatorApiChannel, 2);

    final SafeFuture<BLSSignature> futureSelectionProofValidator1 =
        loader.getCombinedSelectionProofFuture(1, UInt64.ONE, dataStructureUtil.randomSignature());
    final SafeFuture<BLSSignature> futureSelectionProofValidator2 =
        loader.getCombinedSelectionProofFuture(2, UInt64.ONE, dataStructureUtil.randomSignature());

    loader.activate(UInt64.ONE);

    assertThat(futureSelectionProofValidator1)
        .isCompletedWithValue(combinedProofForValidator1.getSelectionProofSignature());
    assertThat(futureSelectionProofValidator2)
        .isCompletedWithValue(combinedProofForValidator2.getSelectionProofSignature());
  }

  @Test
  public void partiallyCompleteFuturesWhenMiddlewareOnlyReturnsSomeSelectionProofs() {
    final BeaconCommitteeSelectionProof combinedProofForValidator1 = combinedProof(1);
    when(validatorApiChannel.getBeaconCommitteeSelectionProof(any()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(List.of(combinedProofForValidator1))));

    loader = new DvtAttestationAggregations(validatorApiChannel, 2);

    final SafeFuture<BLSSignature> futureSelectionProofValidator1 =
        loader.getCombinedSelectionProofFuture(1, UInt64.ONE, dataStructureUtil.randomSignature());
    final SafeFuture<BLSSignature> futureSelectionProofValidator2 =
        loader.getCombinedSelectionProofFuture(2, UInt64.ONE, dataStructureUtil.randomSignature());

    loader.activate(UInt64.ONE);

    assertThat(futureSelectionProofValidator1)
        .isCompletedWithValue(combinedProofForValidator1.getSelectionProofSignature());
    assertThat(futureSelectionProofValidator2).isCompletedExceptionally();
  }

  @Test
  public void failAllFuturesIfMiddlewareDoesNotReturnAnyValue() {
    when(validatorApiChannel.getBeaconCommitteeSelectionProof(any()))
        .thenReturn(SafeFuture.completedFuture(Optional.empty()));

    loader = new DvtAttestationAggregations(validatorApiChannel, 3);

    final SafeFuture<BLSSignature> futureSelectionProofValidator1 =
        loader.getCombinedSelectionProofFuture(1, UInt64.ONE, dataStructureUtil.randomSignature());
    final SafeFuture<BLSSignature> futureSelectionProofValidator2 =
        loader.getCombinedSelectionProofFuture(
            2, UInt64.valueOf(2), dataStructureUtil.randomSignature());
    final SafeFuture<BLSSignature> futureSelectionProofValidator3 =
        loader.getCombinedSelectionProofFuture(
            3, UInt64.valueOf(3), dataStructureUtil.randomSignature());

    loader.activate(UInt64.ONE);

    assertThat(futureSelectionProofValidator1).isCompletedExceptionally();
    assertThat(futureSelectionProofValidator2).isCompletedExceptionally();
    assertThat(futureSelectionProofValidator3).isCompletedExceptionally();
  }

  @Test
  public void handleDifferentValidatorAggregatingInSameSlot() {
    final BeaconCommitteeSelectionProof combinedProofForValidator1 = combinedProofForSlot(1, 1);
    final BeaconCommitteeSelectionProof combinedProofForValidator2 = combinedProofForSlot(2, 1);
    final BeaconCommitteeSelectionProof combinedProofForValidator3 = combinedProofForSlot(3, 1);
    when(validatorApiChannel.getBeaconCommitteeSelectionProof(any()))
        .thenReturn(
            SafeFuture.completedFuture(
                Optional.of(
                    List.of(
                        combinedProofForValidator1,
                        combinedProofForValidator2,
                        combinedProofForValidator3))));

    loader = new DvtAttestationAggregations(validatorApiChannel, 3);

    final SafeFuture<BLSSignature> futureSelectionProofValidator1 =
        loader.getCombinedSelectionProofFuture(1, UInt64.ONE, dataStructureUtil.randomSignature());
    final SafeFuture<BLSSignature> futureSelectionProofValidator2 =
        loader.getCombinedSelectionProofFuture(2, UInt64.ONE, dataStructureUtil.randomSignature());
    final SafeFuture<BLSSignature> futureSelectionProofValidator3 =
        loader.getCombinedSelectionProofFuture(3, UInt64.ONE, dataStructureUtil.randomSignature());

    loader.activate(UInt64.ONE);

    assertThat(futureSelectionProofValidator1)
        .isCompletedWithValue(combinedProofForValidator1.getSelectionProofSignature());
    assertThat(futureSelectionProofValidator2)
        .isCompletedWithValue(combinedProofForValidator2.getSelectionProofSignature());
    assertThat(futureSelectionProofValidator3)
        .isCompletedWithValue(combinedProofForValidator3.getSelectionProofSignature());
  }

  @Test
  public void handleSameValidatorAggregatingInDifferentSlots() {
    final BeaconCommitteeSelectionProof combinedProofForSlot1 = combinedProofForSlot(1, 1);
    final BeaconCommitteeSelectionProof combinedProofForSlot2 = combinedProofForSlot(1, 2);
    when(validatorApiChannel.getBeaconCommitteeSelectionProof(any()))
        .thenReturn(
            SafeFuture.completedFuture(
                Optional.of(List.of(combinedProofForSlot1, combinedProofForSlot2))));

    loader = new DvtAttestationAggregations(validatorApiChannel, 2);

    final SafeFuture<BLSSignature> futureSelectionProofValidatorAtSlot1 =
        loader.getCombinedSelectionProofFuture(1, UInt64.ONE, dataStructureUtil.randomSignature());
    final SafeFuture<BLSSignature> futureSelectionProofValidatorAtSlot2 =
        loader.getCombinedSelectionProofFuture(
            1, UInt64.valueOf(2), dataStructureUtil.randomSignature());

    loader.activate(UInt64.ONE);

    assertThat(futureSelectionProofValidatorAtSlot1)
        .isCompletedWithValue(combinedProofForSlot1.getSelectionProofSignature());
    assertThat(futureSelectionProofValidatorAtSlot2)
        .isCompletedWithValue(combinedProofForSlot2.getSelectionProofSignature());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void
      unexpectedErrorHandlingResponseMustCompleteExceptionallyPendingRequestsWithUnderlyingCause() {
    final List<BeaconCommitteeSelectionProof> mockList = mock(List.class);
    // Forcing an unexpected error when handling response
    when(mockList.stream()).thenThrow(new RuntimeException("Unexpected error"));
    when(validatorApiChannel.getBeaconCommitteeSelectionProof(any()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(mockList)));

    loader = new DvtAttestationAggregations(validatorApiChannel, 1);

    final SafeFuture<BLSSignature> futureSelectionProofValidator1 =
        loader.getCombinedSelectionProofFuture(1, UInt64.ONE, dataStructureUtil.randomSignature());

    loader.activate(UInt64.ONE);

    assertThat(futureSelectionProofValidator1)
        .isCompletedExceptionally()
        .failsWithin(1, TimeUnit.SECONDS)
        .withThrowableOfType(ExecutionException.class)
        .withCauseInstanceOf(RuntimeException.class)
        .withMessageContaining("Error getting DVT attestation aggregation complete proof");
  }

  @Test
  public void unexpectedErrorHandlingResponseMustCompleteExceptionallyAllPendingRequests() {
    final BeaconCommitteeSelectionProof proofValidator2 = spy(combinedProofForSlot(2, 1));
    // Forcing an unexpected error while iterating the response
    when(proofValidator2.getValidatorIndex()).thenThrow(new RuntimeException("Unexpected error"));
    when(validatorApiChannel.getBeaconCommitteeSelectionProof(any()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(List.of(proofValidator2))));

    loader = new DvtAttestationAggregations(validatorApiChannel, 2);

    final SafeFuture<BLSSignature> futureProofValidator1 =
        loader.getCombinedSelectionProofFuture(1, UInt64.ONE, dataStructureUtil.randomSignature());
    final SafeFuture<BLSSignature> futureProofValidator2 =
        loader.getCombinedSelectionProofFuture(
            2, UInt64.valueOf(2), dataStructureUtil.randomSignature());

    loader.activate(UInt64.ONE);

    assertThat(futureProofValidator1).isCompletedExceptionally();
    assertThat(futureProofValidator2).isCompletedExceptionally();
  }

  @Test
  @SuppressWarnings("FutureReturnValueIgnored")
  public void doesNotSubmitBeforeActivation() {
    when(validatorApiChannel.getBeaconCommitteeSelectionProof(any()))
        .thenReturn(
            SafeFuture.completedFuture(Optional.of(List.of(combinedProof(1), combinedProof(2)))));

    loader = new DvtAttestationAggregations(validatorApiChannel, 2);

    loader.getCombinedSelectionProofFuture(1, UInt64.ONE, dataStructureUtil.randomSignature());
    final SafeFuture<BLSSignature> future2 =
        loader.getCombinedSelectionProofFuture(2, UInt64.ONE, dataStructureUtil.randomSignature());

    // Count reached but not yet activated — no HTTP call
    verifyNoInteractions(validatorApiChannel);
    assertThat(future2).isNotDone();

    // Activate — HTTP call fires — futures complete
    loader.activate(UInt64.ONE);
    verify(validatorApiChannel).getBeaconCommitteeSelectionProof(any());
    assertThat(future2).isCompleted();
  }

  @Test
  @SuppressWarnings("FutureReturnValueIgnored")
  public void activateBeforeAllFuturesRegisteredStillSubmitsWhenCountReached() {
    final BeaconCommitteeSelectionProof proof1 = combinedProof(1);
    final BeaconCommitteeSelectionProof proof2 = combinedProof(2);
    when(validatorApiChannel.getBeaconCommitteeSelectionProof(any()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(List.of(proof1, proof2))));

    loader = new DvtAttestationAggregations(validatorApiChannel, 2);

    loader.getCombinedSelectionProofFuture(1, UInt64.ONE, dataStructureUtil.randomSignature());

    // Activate before the second future is registered (simulates onSlot firing before signing
    // completes)
    loader.activate(UInt64.ONE);
    verifyNoInteractions(validatorApiChannel);

    // Second future registered — count now reached, activation already set — should submit
    final SafeFuture<BLSSignature> future2 =
        loader.getCombinedSelectionProofFuture(2, UInt64.ONE, dataStructureUtil.randomSignature());

    verify(validatorApiChannel).getBeaconCommitteeSelectionProof(any());
    assertThat(future2).isCompleted();
  }

  private BeaconCommitteeSelectionProof combinedProof(final int validatorIndex) {
    return new BeaconCommitteeSelectionProof.Builder()
        .validatorIndex(validatorIndex)
        .slot(UInt64.ONE)
        .selectionProof(dataStructureUtil.randomSignature().toBytesCompressed().toHexString())
        .build();
  }

  private BeaconCommitteeSelectionProof combinedProofForSlot(
      final int validatorIndex, final int slot) {
    return new BeaconCommitteeSelectionProof.Builder()
        .validatorIndex(validatorIndex)
        .slot(UInt64.valueOf(slot))
        .selectionProof(dataStructureUtil.randomSignature().toBytesCompressed().toHexString())
        .build();
  }
}

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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.api.response.ValidatorStatus;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.ethereum.json.types.validator.AttesterDuties;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.datastructures.operations.AttesterSlashing;
import tech.pegasys.teku.spec.datastructures.operations.ProposerSlashing;
import tech.pegasys.teku.validator.api.ValidatorApiChannel;
import tech.pegasys.teku.validator.api.ValidatorTimingChannel;
import tech.pegasys.teku.validator.client.duties.BeaconCommitteeSubscriptions;
import tech.pegasys.teku.validator.client.duties.SlotBasedScheduledDuties;
import tech.pegasys.teku.validator.client.duties.attestations.AggregationDuty;
import tech.pegasys.teku.validator.client.duties.attestations.AttestationProductionDuty;
import tech.pegasys.teku.validator.client.loader.OwnedValidators;

public class AttestationDutyDefaultSchedulingStrategy
    extends AbstractAttestationDutySchedulingStrategy implements ValidatorTimingChannel {

  private final ValidatorApiChannel validatorApiChannel;
  private final boolean useDvtEndpoint;
  private final AtomicReference<UInt64> currentSlot = new AtomicReference<>(UInt64.ZERO);
  private final ConcurrentHashMap<UInt64, DvtAttestationAggregations> pendingDvtByEpoch =
      new ConcurrentHashMap<>();

  public AttestationDutyDefaultSchedulingStrategy(
      final Spec spec,
      final ForkProvider forkProvider,
      final Function<Bytes32, SlotBasedScheduledDuties<AttestationProductionDuty, AggregationDuty>>
          scheduledDutiesFactory,
      final OwnedValidators validators,
      final BeaconCommitteeSubscriptions beaconCommitteeSubscriptions,
      final ValidatorApiChannel validatorApiChannel,
      final boolean useDvtEndpoint) {
    super(spec, forkProvider, scheduledDutiesFactory, validators, beaconCommitteeSubscriptions);
    this.validatorApiChannel = validatorApiChannel;
    this.useDvtEndpoint = useDvtEndpoint;
  }

  @Override
  public void onSlot(final UInt64 slot) {
    currentSlot.set(slot);
    final UInt64 currentEpoch = spec.computeEpochAtSlot(slot);
    pendingDvtByEpoch
        .entrySet()
        .removeIf(
            entry -> {
              if (entry.getKey().isLessThanOrEqualTo(currentEpoch)) {
                entry.getValue().activate(spec.computeStartSlotAtEpoch(entry.getKey()));
                return true;
              }
              return false;
            });
  }

  @Override
  public SafeFuture<SlotBasedScheduledDuties<?, ?>> scheduleAllDuties(
      final UInt64 epoch, final AttesterDuties duties) {
    final SlotBasedScheduledDuties<AttestationProductionDuty, AggregationDuty> scheduledDuties =
        getScheduledDuties(duties);

    final Optional<DvtAttestationAggregations> dvtAttestationAggregations;
    if (useDvtEndpoint && !duties.getDuties().isEmpty()) {
      final DvtAttestationAggregations dvt =
          new DvtAttestationAggregations(validatorApiChannel, epoch, duties.getDuties().size());
      final boolean isCurrentEpoch =
          epoch.isLessThanOrEqualTo(spec.computeEpochAtSlot(currentSlot.get()));
      if (isCurrentEpoch) {
        dvt.activate(spec.computeStartSlotAtEpoch(epoch));
      } else {
        final DvtAttestationAggregations previous = pendingDvtByEpoch.put(epoch, dvt);
        if (previous != null) {
          previous.cancel();
        }
      }
      dvtAttestationAggregations = Optional.of(dvt);
    } else {
      dvtAttestationAggregations = Optional.empty();
    }

    return scheduleDuties(scheduledDuties, duties.getDuties(), dvtAttestationAggregations)
        .<SlotBasedScheduledDuties<?, ?>>thenApply(__ -> scheduledDuties)
        .alwaysRun(beaconCommitteeSubscriptions::sendRequests);
  }

  @Override
  public void onHeadUpdate(
      final UInt64 slot,
      final Bytes32 previousDutyDependentRoot,
      final Bytes32 currentDutyDependentRoot,
      final Bytes32 headBlockRoot) {}

  @Override
  public void onPossibleMissedEvents() {}

  @Override
  public void onValidatorsAdded() {}

  @Override
  public void onBlockProductionDue(final UInt64 slot) {}

  @Override
  public void onAttestationCreationDue(final UInt64 slot) {}

  @Override
  public void onAttestationAggregationDue(final UInt64 slot) {}

  @Override
  public void onSyncCommitteeCreationDue(final UInt64 slot) {}

  @Override
  public void onContributionCreationDue(final UInt64 slot) {}

  @Override
  public void onPayloadAttestationCreationDue(final UInt64 slot) {}

  @Override
  public void onAttesterSlashing(final AttesterSlashing attesterSlashing) {}

  @Override
  public void onProposerSlashing(final ProposerSlashing proposerSlashing) {}

  @Override
  public void onUpdatedValidatorStatuses(
      final Map<BLSPublicKey, ValidatorStatus> newValidatorStatuses,
      final boolean possibleMissingEvents) {}
}

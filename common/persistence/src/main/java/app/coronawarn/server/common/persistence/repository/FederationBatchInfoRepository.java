

package app.coronawarn.server.common.persistence.repository;

import app.coronawarn.server.common.persistence.domain.FederationBatchInfo;
import app.coronawarn.server.common.persistence.domain.FederationBatchSourceSystem;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FederationBatchInfoRepository extends PagingAndSortingRepository<FederationBatchInfo, String> {

  @Modifying
  @Query("INSERT INTO federation_batch_info "
      + "(batch_tag, date, status, source_system) "
      + "VALUES (:batchTag, :date, :status, :sourceSystem) "
      + "ON CONFLICT DO NOTHING")
  boolean saveDoNothingOnConflict(
      @Param("batchTag") String batchTag,
      @Param("date") LocalDate date,
      @Param("status") String status,
      @Param("sourceSystem") FederationBatchSourceSystem sourceSystem);

  @Modifying
  @Query("INSERT INTO federation_batch_info "
      + "(batch_tag, date, status, source_system) "
      + "VALUES (:batchTag, :date, :status, :sourceSystem) "
      + "ON CONFLICT (batch_tag) DO UPDATE SET status=:status")
  void saveDoUpdateStatusOnConflict(
      @Param("batchTag") String batchTag,
      @Param("date") LocalDate date,
      @Param("status") String status,
      @Param("sourceSystem") FederationBatchSourceSystem sourceSystem);

  List<FederationBatchInfo> findByStatus(@Param("status") String status);

  @Query("SELECT COUNT(*) FROM federation_batch_info WHERE date<:threshold")
  int countOlderThan(@Param("threshold") LocalDate date);

  @Modifying
  @Query("DELETE FROM federation_batch_info WHERE date<:threshold")
  void deleteOlderThan(@Param("threshold") LocalDate date);

  @Query("SELECT COUNT(*) FROM federation_batch_info WHERE date=:date")
  int countForDate(@Param("date") LocalDate date);

  @Modifying
  @Query("DELETE FROM federation_batch_info WHERE date=:date")
  void deleteForDate(@Param("date") LocalDate date);
}

package com.fiipractic.stocks.repository;

import com.fiipractic.stocks.model.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {
    List<Portfolio> findByUserId(String userId);

    @Query("SELECT p FROM Portfolio p " +
            " JOIN FETCH p.holdings h " +
            " JOIN FETCH h.stock")
    List<Portfolio> findAllPortfolios();

}

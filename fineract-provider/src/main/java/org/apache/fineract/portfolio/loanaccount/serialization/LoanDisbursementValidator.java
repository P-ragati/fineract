/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.serialization;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidLoanStateTransitionException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanDisbursalException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class LoanDisbursementValidator {

    private final LoanApplicationValidator loanApplicationValidator;

    public void compareDisbursedToApprovedOrProposedPrincipal(final Loan loan, final BigDecimal totalDisbursed) {
        final BigDecimal totalCapitalizedIncome = loan.getSummary().getTotalCapitalizedIncome();
        final BigDecimal totalCapitalizedIncomeAdjustment = MathUtil.nullToZero(loan.getSummary().getTotalCapitalizedIncomeAdjustment());
        final BigDecimal netCapitalizedIncome = totalCapitalizedIncome.subtract(totalCapitalizedIncomeAdjustment);

        // Always use the same logic for over-applied config and fallback
        LoanProduct loanProduct = loan.getLoanProduct();
        BigDecimal approvedPrincipal = loan.getApprovedPrincipal();
        BigDecimal maxAllowed = approvedPrincipal;

        if (loanProduct.getOverAppliedCalculationType() != null && loanProduct.getOverAppliedNumber() != null) {
            BigDecimal overAppliedNumber = BigDecimal.valueOf(loanProduct.getOverAppliedNumber());
            if ("percentage".equalsIgnoreCase(loanProduct.getOverAppliedCalculationType())) {
                BigDecimal extra = approvedPrincipal.multiply(overAppliedNumber).divide(BigDecimal.valueOf(100));
                maxAllowed = approvedPrincipal.add(extra);
            } else {
                // ABSOLUTE (flat)
                maxAllowed = approvedPrincipal.add(overAppliedNumber);
            }
        }

        BigDecimal total = totalDisbursed.add(netCapitalizedIncome);
        if (maxAllowed == null) {
            maxAllowed = approvedPrincipal;
        }
        if (total.compareTo(maxAllowed) > 0) {
            throw new LoanDisbursalException(
                "Disbursement exceeds allowed limit including over-applied threshold",
                "disburse.amount.must.be.less.than.or.equal.to.max.allowed", total, maxAllowed);
        }
    }

    public void validateOverMaximumAmount(final Loan loan, final BigDecimal totalDisbursed, final BigDecimal capitalizedIncome) {
        LoanProduct loanProduct = loan.getLoanProduct();
        BigDecimal approvedPrincipal = loan.getApprovedPrincipal();
        BigDecimal maxAllowed = approvedPrincipal;

        if (loanProduct.getOverAppliedCalculationType() != null && loanProduct.getOverAppliedNumber() != null) {
            BigDecimal overAppliedNumber = BigDecimal.valueOf(loanProduct.getOverAppliedNumber());
            if ("percentage".equalsIgnoreCase(loanProduct.getOverAppliedCalculationType())) {
                BigDecimal extra = approvedPrincipal.multiply(overAppliedNumber).divide(BigDecimal.valueOf(100));
                maxAllowed = approvedPrincipal.add(extra);
            } else {
                // ABSOLUTE (flat)
                maxAllowed = approvedPrincipal.add(overAppliedNumber);
            }
        }

        BigDecimal total = totalDisbursed.add(capitalizedIncome);
        if (maxAllowed == null) {
            maxAllowed = approvedPrincipal;
        }
        if (total.compareTo(maxAllowed) > 0) {
            final String errorMessage = String.format(
                "Disbursement exceeds allowed limit including over-applied threshold. Total disbursed amount: %s  Maximum allowed: %s",
                total.stripTrailingZeros().toPlainString(), maxAllowed.stripTrailingZeros().toPlainString());
            throw new InvalidLoanStateTransitionException("disbursal",
                "amount.can't.be.greater.than.maximum.applied.loan.amount.calculation", errorMessage, total, maxAllowed);
        }
    }

    public void validateDisburseDate(final Loan loan, final LocalDate disbursedOn, final LocalDate expectedDate) {
        if (expectedDate != null
                && (DateUtils.isAfter(disbursedOn, loan.fetchRepaymentScheduleInstallment(1).getDueDate())
                        || DateUtils.isAfter(disbursedOn, expectedDate))
                && DateUtils.isEqual(disbursedOn, loan.getActualDisbursementDate())) {
            final String errorMessage = "submittedOnDate cannot be after the loans  expectedFirstRepaymentOnDate: " + expectedDate;
            throw new InvalidLoanStateTransitionException("disbursal", "cannot.be.after.expected.first.repayment.date", errorMessage,
                    disbursedOn, expectedDate);
        }

        if (DateUtils.isDateInTheFuture(disbursedOn)) {
            final String errorMessage = "The date on which a loan with identifier : " + loan.getAccountNumber()
                    + " is disbursed cannot be in the future.";
            throw new InvalidLoanStateTransitionException("disbursal", "cannot.be.a.future.date", errorMessage, disbursedOn);
        }
    }
}

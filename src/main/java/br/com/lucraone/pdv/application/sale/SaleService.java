package br.com.lucraone.pdv.application.sale;

import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.application.catalog.ActiveCatalogRepository;
import br.com.lucraone.pdv.application.catalog.CatalogCacheContext;
import br.com.lucraone.pdv.application.catalog.CatalogPackage;
import br.com.lucraone.pdv.application.catalog.CatalogPrice;
import br.com.lucraone.pdv.application.catalog.CatalogProduct;
import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.domain.sale.Sale;
import br.com.lucraone.pdv.domain.sale.SaleItem;
import br.com.lucraone.pdv.domain.sale.SalePayment;
import br.com.lucraone.pdv.domain.sale.PaymentConfirmationOrigin;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Coordinates sale authorization, active local catalog, payment validation and persistence. */
public final class SaleService {

    private static final int QUANTITY_SCALE = 4;

    private final SaleRepository sales;
    private final ActiveCatalogRepository catalog;
    private final SaleAuthorizationProvider authorization;
    private final ElectronicPaymentAuthorizer electronicPayments;
    private final Clock clock;

    public SaleService(SaleRepository sales, ActiveCatalogRepository catalog,
            SaleAuthorizationProvider authorization, ElectronicPaymentAuthorizer electronicPayments,
            Clock clock) {
        this.sales = Objects.requireNonNull(sales, "sales must not be null");
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.electronicPayments = Objects.requireNonNull(electronicPayments, "electronicPayments must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Prices, names, units and package details are resolved only from the active local catalog. */
    public Sale createConfirmedSale(CreateSaleCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        AuthorizedSaleContext context = currentContext(SaleAuthorizationAction.CREATE_SALE);
        AuthorizedSaleContext paymentContext = currentContext(SaleAuthorizationAction.CONFIRM_MANUAL_PAYMENT);
        if (!context.operatorSession().operatorId().equals(paymentContext.operatorSession().operatorId())
                || !context.terminalContext().equals(paymentContext.terminalContext())
                || !context.currency().equals(paymentContext.currency())) {
            throw new IllegalStateException("Payment confirmation authorization context changed.");
        }
        String currency = context.currency();
        Money.zero(currency); // validates the configured ISO 4217 currency
        CatalogCacheContext catalogContext = CatalogCacheContext.from(context.terminalContext());

        List<SaleItem> items = new ArrayList<>(command.items().size());
        int lineNumber = 1;
        for (SaleLineCommand requested : command.items()) {
            validateQuantity(requested.quantity());
            requireCurrency(requested.discountAmount(), currency, "item discount");
            CatalogProduct product = catalog.findById(catalogContext, requested.productId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Product is not present in the active local catalog: " + requested.productId()));
            if (!product.id().equals(requested.productId())) {
                throw new IllegalStateException("Active catalog returned a different product than requested.");
            }
            if (!"active".equalsIgnoreCase(product.status())) {
                throw new IllegalStateException("Product is inactive in the active local catalog: " + product.id());
            }
            if (product.unit().isBlank()) {
                throw new IllegalStateException("Catalog product has no usable unit: " + product.id());
            }
            CatalogPrice price = product.salePrices().stream()
                    .filter(candidate -> candidate.currency().equals(currency))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No active catalog price for currency " + currency + " and product " + product.id()));
            Optional<CatalogPackage> selectedPackage = requested.packageId().map(packageId -> product.packages().stream()
                    .filter(candidate -> candidate.id().equals(packageId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Package is not available for product " + product.id() + ": " + packageId)));
            Money unitPrice = new Money(price.amount(), price.currency());
            items.add(SaleItem.of(lineNumber++, product.id(), product.name(), Optional.ofNullable(product.sku()),
                    product.unit(), requested.quantity(), unitPrice, requested.discountAmount(),
                    selectedPackage.map(CatalogPackage::id), selectedPackage.map(CatalogPackage::name),
                    selectedPackage.map(CatalogPackage::factor)));
        }

        requireCurrency(command.discountAmount(), currency, "sale discount");
        requireCurrency(command.paymentAmount(), currency, "payment amount");
        Money subtotal = items.stream().map(SaleItem::totalAmount)
                .reduce(Money.zero(currency), Money::add);
        Money total = subtotal.subtract(command.discountAmount());
        SalePayment payment = validatedPayment(command, total);

        Sale sale = Sale.confirm(command.saleId(), context.terminalContext().terminal().id(),
                context.terminalContext().tenant().id(), context.terminalContext().company().id(),
                context.terminalContext().branch().id(), context.operatorSession().operatorId(),
                context.operatorSession().operatorName(), currency, items, command.discountAmount(),
                payment, clock.instant());
        return sales.saveConfirmed(sale);
    }

    public Optional<Sale> findById(UUID saleId) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        AuthorizedSaleContext context = currentContext(SaleAuthorizationAction.READ_SALES);
        return sales.findById(saleId).filter(sale -> belongsTo(sale, context));
    }

    public List<Sale> listForCurrentTerminal() {
        AuthorizedSaleContext context = currentContext(SaleAuthorizationAction.READ_SALES);
        return sales.findByTerminalId(context.terminalContext().terminal().id()).stream()
                .filter(sale -> belongsTo(sale, context))
                .toList();
    }

    public List<ManualPaymentAttemptSummary> listManualPaymentReconciliation() {
        AuthorizedSaleContext context = currentContext(SaleAuthorizationAction.READ_SALES);
        return sales.findManualPaymentAttemptsForReconciliation(context.terminalContext().terminal().id());
    }

    /** Commercial cancellation only; financial refunds and fiscal cancellation are separate workflows. */
    public Sale cancel(UUID saleId, String reason) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        AuthorizedSaleContext context = currentContext(SaleAuthorizationAction.CANCEL_SALE);
        Sale existing = sales.findById(saleId)
                .filter(sale -> belongsTo(sale, context))
                .orElseThrow(() -> new NoSuchElementException("Sale not found in the authorized terminal."));
        if (existing.fiscalDocumentType().isPresent() || existing.fiscalNumber().isPresent()) {
            throw new IllegalStateException(
                    "Sale has fiscal data; a fiscal cancellation workflow is required before commercial cancellation.");
        }
        if (existing.payment().method() != PaymentMethod.CASH) {
            throw new IllegalStateException(
                    "Electronic or unclassified payment requires a financial reversal workflow before cancellation.");
        }
        return sales.cancel(saleId, reason, clock.instant());
    }

    private SalePayment validatedPayment(CreateSaleCommand command, Money total) {
        if (command.paymentMethod() == PaymentMethod.OTHER) {
            throw new IllegalStateException("OTHER is disabled for MVP sales.");
        }
        if (command.paymentMethod() == PaymentMethod.CASH) {
            if (command.manualDeclaration() != ManualPaymentDeclaration.CASH_RECEIVED) {
                throw new IllegalArgumentException("Cash requires a CASH_RECEIVED declaration.");
            }
            if (command.paymentAmount().amount().compareTo(total.amount()) < 0) {
                throw new IllegalArgumentException("Cash received is insufficient for the sale total.");
            }
            Money change = command.paymentAmount().subtract(total);
            return new SalePayment(PaymentMethod.CASH, command.paymentAmount(), change,
                    PaymentConfirmationOrigin.MANUAL);
        }

        if (command.paymentMethod() != PaymentMethod.CARD_DEBIT
                && command.paymentMethod() != PaymentMethod.CARD_CREDIT
                && command.paymentMethod() != PaymentMethod.PIX) {
            throw new IllegalStateException("This payment method has no verifiable authorization contract.");
        }
        if (command.paymentAmount().amount().compareTo(total.amount()) != 0) {
            throw new IllegalArgumentException("Manual payment amount must equal the sale total.");
        }
        ManualPaymentDeclaration expected = command.paymentMethod() == PaymentMethod.PIX
                ? ManualPaymentDeclaration.PIX_OFFICIAL_CHANNEL_CHECKED
                : ManualPaymentDeclaration.EXTERNAL_CARD_APPROVED;
        if (command.manualDeclaration() != expected) {
            throw new IllegalStateException("An explicit method-specific manual payment declaration is required.");
        }
        return new SalePayment(command.paymentMethod(), total, Money.zero(total.currency()),
                PaymentConfirmationOrigin.MANUAL);
    }

    private AuthorizedSaleContext currentContext(SaleAuthorizationAction action) {
        AuthorizedSaleContext context = Objects.requireNonNull(
                authorization.requireAuthorizedContext(action), "authorization returned no context");
        TerminalContext terminal = context.terminalContext();
        if (!"active".equalsIgnoreCase(terminal.terminal().status())) {
            throw new IllegalStateException("Terminal is not active for sale operations.");
        }
        if (!"active".equalsIgnoreCase(context.operatorSession().status())
                || !context.operatorSession().expiresAt().isAfter(clock.instant())) {
            throw new IllegalStateException("Operator session is not active or has expired.");
        }
        requireNonBlank(context.operatorSession().operatorId(), "operatorId");
        requireNonBlank(context.operatorSession().operatorName(), "operatorName");
        return context;
    }

    private static boolean belongsTo(Sale sale, AuthorizedSaleContext context) {
        TerminalContext terminal = context.terminalContext();
        return sale.terminalId().equals(terminal.terminal().id())
                && sale.tenantId().equals(terminal.tenant().id())
                && sale.companyId().equals(terminal.company().id())
                && sale.branchId().equals(terminal.branch().id());
    }

    private static void validateQuantity(BigDecimal quantity) {
        if (quantity.signum() <= 0) throw new IllegalArgumentException("Quantity must be positive.");
        if (quantity.stripTrailingZeros().scale() > QUANTITY_SCALE) {
            throw new IllegalArgumentException("Quantity supports at most four decimal places.");
        }
        if (quantity.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY).signum() <= 0) {
            throw new IllegalArgumentException("Quantity must remain positive at the supported scale.");
        }
    }

    private static void requireCurrency(Money amount, String currency, String label) {
        if (!amount.currency().equals(currency)) {
            throw new IllegalArgumentException(label + " currency must be " + currency);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is missing.");
    }
}

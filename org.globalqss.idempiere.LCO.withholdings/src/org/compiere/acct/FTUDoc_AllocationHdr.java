package org.compiere.acct;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.compiere.acct.Doc_AllocationHdr;
import org.compiere.model.MAccount;
import org.compiere.model.MAcctSchema;
import org.compiere.model.MAcctSchemaElement;
import org.compiere.model.MAllocationHdr;
import org.compiere.model.MAllocationLine;
import org.compiere.model.MCashLine;
import org.compiere.model.MConversionRate;
import org.compiere.model.MFactAcct;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MOrder;
import org.compiere.model.MPayment;
import org.compiere.model.MPeriod;
import org.compiere.util.DB;
import org.compiere.util.Env;

public class FTUDoc_AllocationHdr extends Doc_AllocationHdr {

	public FTUDoc_AllocationHdr(MAcctSchema as, ResultSet rs, String trxName) {
		super(as, rs, trxName);
	}

	/**	Tolerance G&L				*/
	private static final BigDecimal	TOLERANCE = BigDecimal.valueOf(0.02);
	/** Facts						*/
	private ArrayList<Fact>		m_facts = null;
	BigDecimal gainLossAmt =Env.ZERO;
	private BigDecimal cmGainLossAmt=Env.ZERO;
	private ArrayList<FactLine> gainLossFactList;


	/**
	 *  Load Specific Document Details
	 *  @return error message or null
	 */
	protected String loadDocumentDetails ()
	{
		MAllocationHdr alloc = (MAllocationHdr)getPO();
		setDateDoc(alloc.getDateTrx());
		//	Contained Objects
		p_lines = loadLines(alloc);
		return null;
	}   //  loadDocumentDetails

	/**
	 *	Load Invoice Line
	 *	@param alloc header
	 *  @return DocLine Array
	 */
	private DocLine[] loadLines(MAllocationHdr alloc)
	{
		ArrayList<DocLine> list = new ArrayList<DocLine>();
		MAllocationLine[] lines = alloc.getLines(false);
		for (int i = 0; i < lines.length; i++)
		{
			MAllocationLine line = lines[i];
			DocLine_Allocation docLine = new DocLine_Allocation(line, this);
			//
			if (log.isLoggable(Level.FINE)) log.fine(docLine.toString());
			list.add (docLine);
		}

		//	Return Array
		DocLine[] dls = new DocLine[list.size()];
		list.toArray(dls);
		return dls;
	}	//	loadLines


	/**************************************************************************
	 *  Get Source Currency Balance - subtracts line and tax amounts from total - no rounding
	 *  @return positive amount, if total invoice is bigger than lines
	 */
	public BigDecimal getBalance()
	{
		BigDecimal retValue = Env.ZERO;
		return retValue;
	}   //  getBalance

	/**
	 *  Create Facts (the accounting logic) for
	 *  CMA.
	 *  <pre>
	 *  AR_Invoice_Payment
	 *      UnAllocatedCash DR
	 *      or C_Prepayment
	 *      DiscountExp     DR
	 *      WriteOff        DR
	 *      Receivables             CR
	 *  AR_Invoice_Cash
	 *      CashTransfer    DR
	 *      DiscountExp     DR
	 *      WriteOff        DR
	 *      Receivables             CR
	 *
	 *  AP_Invoice_Payment
	 *      Liability       DR
	 *      DiscountRev             CR
	 *      WriteOff                CR
	 *      PaymentSelect           CR
	 *      or V_Prepayment
	 *  AP_Invoice_Cash
	 *      Liability       DR
	 *      DiscountRev             CR
	 *      WriteOff                CR
	 *      CashTransfer            CR
	 *  CashBankTransfer
	 *      -
	 *  ==============================
	 *  Realized Gain & Loss
	 * 		AR/AP			DR		CR
	 * 		Realized G/L	DR		CR
	 *
	 *
	 *  </pre>
	 *  Tax needs to be corrected for discount & write-off;
	 *  Currency gain & loss is realized here.
	 *  @param as accounting schema
	 *  @return Fact
	 */
	public ArrayList<Fact> createFacts (MAcctSchema as)
	{
		m_facts = new ArrayList<Fact>();

		//  create Fact Header
		Fact fact = new Fact(this, as, Fact.POST_Actual);
		Fact factForRGL = new Fact(this, as, Fact.POST_Actual); // dummy fact (not posted) to calculate Realized Gain & Loss
		boolean isInterOrg = isInterOrg(as);
		BigDecimal paymentSelectAmt = Env.ZERO;		
		BigDecimal totalAllocationSource = Env.ZERO;
		MPayment payment = null;
		int lineID = 0;
		MAccount bpAcct = null;		//	Liability/Receivables
		gainLossFactList = new ArrayList<FactLine>();

		for (int i = 0; i < p_lines.length; i++)
		{
			DocLine_Allocation line = (DocLine_Allocation)p_lines[i];
			setC_BPartner_ID(line.getC_BPartner_ID());

			//  CashBankTransfer - all references null and Discount/WriteOff = 0
			if (line.getC_Payment_ID() != 0
				&& line.getC_Invoice_ID() == 0 && line.getC_Order_ID() == 0
				&& line.getC_CashLine_ID() == 0 && line.getC_BPartner_ID() == 0
				&& Env.ZERO.compareTo(line.getDiscountAmt()) == 0
				&& Env.ZERO.compareTo(line.getWriteOffAmt()) == 0)
				continue;

			//	Receivables/Liability Amt
			BigDecimal allocationSource = line.getAmtSource()
				.add(line.getDiscountAmt())
				.add(line.getWriteOffAmt());
			BigDecimal allocationSourceForRGL = allocationSource; // for realized gain & loss purposes
			BigDecimal allocationAccounted = Env.ZERO;	// AR/AP balance corrected
			@SuppressWarnings("unused")
			BigDecimal allocationAccountedForRGL = Env.ZERO; // for realized gain & loss purposes

			FactLine fl = null;
			FactLine flForRGL = null;
			//
			if (line.getC_Payment_ID() != 0)
				payment = new MPayment (getCtx(), line.getC_Payment_ID(), getTrxName());
			MInvoice invoice = null;
			if (line.getC_Invoice_ID() != 0)
				invoice = new MInvoice (getCtx(), line.getC_Invoice_ID(), getTrxName());

			//	No Invoice
			if (invoice == null)
			{
					//	adaxa-pb: allocate to charges
			    	// Charge Only 
				if (line.getC_Invoice_ID() == 0 && line.getC_Payment_ID() == 0 && line.getC_Charge_ID() != 0 )
				{
					fl = fact.createLine (line, line.getChargeAccount(as, line.getAmtSource()),
						getC_Currency_ID(), line.getAmtSource());
				}
				//	Payment Only
				else if (line.getC_Invoice_ID() == 0 && line.getC_Payment_ID() != 0)
				{
					fl = fact.createLine (line, getPaymentAcct(as, line.getC_Payment_ID()),
						getC_Currency_ID(), line.getAmtSource(), null);
					if (fl != null && payment != null)
						fl.setAD_Org_ID(payment.getAD_Org_ID());
				}
				else
				{
					p_Error = "Cannot determine SO/PO";
					log.log(Level.SEVERE, p_Error);
					return null;
				}
			}
			//	Sales Invoice
			else if (invoice.isSOTrx())
			{

				// Avoid usage of clearing accounts
				// If both accounts Unallocated Cash and Receivable are equal
				// then don't post

				MAccount acct_unallocated_cash = null;
				if (line.getC_Payment_ID() != 0)
					acct_unallocated_cash =  getPaymentAcct(as, line.getC_Payment_ID());
				else if (line.getC_CashLine_ID() != 0)
					acct_unallocated_cash =  getCashAcct(as, line.getC_CashLine_ID());
				MAccount acct_receivable = getAccount(Doc.ACCTTYPE_C_Receivable, as);

				if ((!as.isPostIfClearingEqual()) && acct_unallocated_cash != null && acct_unallocated_cash.equals(acct_receivable) && (!isInterOrg)) {

					// if not using clearing accounts, then don't post amtsource
					// change the allocationsource to be writeoff + discount
					allocationSource = line.getDiscountAmt().add(line.getWriteOffAmt());


				} else {

					// Normal behavior -- unchanged if using clearing accounts

					//	Payment/Cash	DR
					if (line.getC_Payment_ID() != 0)
					{
						fl = fact.createLine (line, getPaymentAcct(as, line.getC_Payment_ID()),
							getC_Currency_ID(), line.getAmtSource(), null);
						if (fl != null && payment != null)
							fl.setAD_Org_ID(payment.getAD_Org_ID());
						if (payment.getReversal_ID() > 0 )
							paymentSelectAmt= paymentSelectAmt.add(fl.getAcctBalance().negate());
						else
							paymentSelectAmt= paymentSelectAmt.add(fl.getAcctBalance());
					}
					else if (line.getC_CashLine_ID() != 0)
					{
						fl = fact.createLine (line, getCashAcct(as, line.getC_CashLine_ID()),
							getC_Currency_ID(), line.getAmtSource(), null);
						MCashLine cashLine = new MCashLine (getCtx(), line.getC_CashLine_ID(), getTrxName());
						if (fl != null && cashLine.get_ID() != 0)
							fl.setAD_Org_ID(cashLine.getAD_Org_ID());
					}

				}
				// End Avoid usage of clearing accounts

				//	Discount		DR
				if (Env.ZERO.compareTo(line.getDiscountAmt()) != 0)
				{
					fl = fact.createLine (line, getAccount(Doc.ACCTTYPE_DiscountExp, as),
						getC_Currency_ID(), line.getDiscountAmt(), null);
					if (fl != null && payment != null)
						fl.setAD_Org_ID(payment.getAD_Org_ID());
				}
				//	Write off		DR
				if (Env.ZERO.compareTo(line.getWriteOffAmt()) != 0)
				{
					fl = fact.createLine (line, getAccount(Doc.ACCTTYPE_WriteOff, as),
						getC_Currency_ID(), line.getWriteOffAmt(), null);
					if (fl != null && payment != null)
						fl.setAD_Org_ID(payment.getAD_Org_ID());
				}

				//	AR Invoice Amount	CR
				if (as.isAccrual())
				{
					bpAcct = getAccount(Doc.ACCTTYPE_C_Receivable, as);
					fl = fact.createLine (line, bpAcct,
						getC_Currency_ID(), null, allocationSource);		//	payment currency
					if (fl != null)
						allocationAccounted = fl.getAcctBalance().negate();
					if (fl != null && invoice != null)
						fl.setAD_Org_ID(invoice.getAD_Org_ID());

					// for Realized Gain & Loss
					flForRGL = factForRGL.createLine (line, bpAcct,
						getC_Currency_ID(), null, allocationSourceForRGL);		//	payment currency
					if (flForRGL != null)
						allocationAccountedForRGL = flForRGL.getAcctBalance().negate();
				}
				else	//	Cash Based
				{
					allocationAccounted = createCashBasedAcct (as, fact,
						invoice, allocationSource);
					allocationAccountedForRGL = allocationAccounted;
				}
			}
			//	Purchase Invoice
			else
			{
				// Avoid usage of clearing accounts
				// If both accounts Payment Select and Liability are equal
				// then don't post

				MAccount acct_payment_select = null;
				if (line.getC_Payment_ID() != 0)
					acct_payment_select = getPaymentAcct(as, line.getC_Payment_ID());
				else if (line.getC_CashLine_ID() != 0)
					acct_payment_select = getCashAcct(as, line.getC_CashLine_ID());
				MAccount acct_liability = getAccount(Doc.ACCTTYPE_V_Liability, as);
				boolean isUsingClearing = true;

				// Save original allocation source for realized gain & loss purposes
				allocationSourceForRGL = allocationSourceForRGL.negate();

				if ((!as.isPostIfClearingEqual()) && acct_payment_select != null && acct_payment_select.equals(acct_liability) && (!isInterOrg)) {

					// if not using clearing accounts, then don't post amtsource
					// change the allocationsource to be writeoff + discount
					allocationSource = line.getDiscountAmt().add(line.getWriteOffAmt());
					isUsingClearing = false;

				}
				// End Avoid usage of clearing accounts

				allocationSource = allocationSource.negate();	//	allocation is negative
				//	AP Invoice Amount	DR
				if (as.isAccrual())
				{
					bpAcct = getAccount(Doc.ACCTTYPE_V_Liability, as);
					fl = fact.createLine (line, bpAcct,
						getC_Currency_ID(), allocationSource, null);		//	payment currency
					if (fl != null)
						allocationAccounted = fl.getAcctBalance();
					if (fl != null && invoice != null)
						fl.setAD_Org_ID(invoice.getAD_Org_ID());

					// for Realized Gain & Loss
					flForRGL = factForRGL.createLine (line, bpAcct,
						getC_Currency_ID(), allocationSourceForRGL, null);		//	payment currency
					if (flForRGL != null)
						allocationAccountedForRGL = flForRGL.getAcctBalance();
				}
				else	//	Cash Based
				{
					allocationAccounted = createCashBasedAcct (as, fact,
						invoice, allocationSource);
					allocationAccountedForRGL = allocationAccounted;
				}

				//	Discount		CR
				if (Env.ZERO.compareTo(line.getDiscountAmt()) != 0)
				{
					fl = fact.createLine (line, getAccount(Doc.ACCTTYPE_DiscountRev, as),
						getC_Currency_ID(), null, line.getDiscountAmt().negate());
					if (fl != null && payment != null)
						fl.setAD_Org_ID(payment.getAD_Org_ID());
				}
				//	Write off		CR
				if (Env.ZERO.compareTo(line.getWriteOffAmt()) != 0)
				{
					fl = fact.createLine (line, getAccount(Doc.ACCTTYPE_WriteOff, as),
						getC_Currency_ID(), null, line.getWriteOffAmt().negate());
					if (fl != null && payment != null)
						fl.setAD_Org_ID(payment.getAD_Org_ID());
				}
				//	Payment/Cash	CR
				if (isUsingClearing && line.getC_Payment_ID() != 0) // Avoid usage of clearing accounts
				{
					fl = fact.createLine (line, getPaymentAcct(as, line.getC_Payment_ID()),
						getC_Currency_ID(), null, line.getAmtSource().negate());
					if (fl != null && payment != null)
						fl.setAD_Org_ID(payment.getAD_Org_ID());
					// Fixed bug when process Withholding Allocation
					if(fl != null)
						paymentSelectAmt= paymentSelectAmt.add(fl.getAcctBalance().negate());
				}
				else if (isUsingClearing && line.getC_CashLine_ID() != 0) // Avoid usage of clearing accounts
				{
					fl = fact.createLine (line, getCashAcct(as, line.getC_CashLine_ID()),
						getC_Currency_ID(), null, line.getAmtSource().negate());
					MCashLine cashLine = new MCashLine (getCtx(), line.getC_CashLine_ID(), getTrxName());
					if (fl != null && cashLine.get_ID() != 0)
						fl.setAD_Org_ID(cashLine.getAD_Org_ID());
				}
			}

			//	VAT Tax Correction
			if (invoice != null && as.isTaxCorrection())
			{
				BigDecimal taxCorrectionAmt = Env.ZERO;
				if (as.isTaxCorrectionDiscount())
					taxCorrectionAmt = line.getDiscountAmt();
				if (as.isTaxCorrectionWriteOff())
					taxCorrectionAmt = taxCorrectionAmt.add(line.getWriteOffAmt());
				//
				if (taxCorrectionAmt.signum() != 0)
				{
					if (!createTaxCorrection(as, fact, line,
						getAccount(invoice.isSOTrx() ? Doc.ACCTTYPE_DiscountExp : Doc.ACCTTYPE_DiscountRev, as),
						getAccount(Doc.ACCTTYPE_WriteOff, as), invoice.isSOTrx()))
					{
						p_Error = "Cannot create Tax correction";
						return null;
					}
				}
			}

			//	Realized Gain & Loss
			if (invoice != null
				&& (getC_Currency_ID() != as.getC_Currency_ID()			//	payment allocation in foreign currency
					|| getC_Currency_ID() != line.getInvoiceC_Currency_ID()))	//	allocation <> invoice currency
			{
				p_Error = createRealizedGainLoss (line, as, fact, bpAcct, invoice,
					allocationSource, allocationAccounted);
				if (p_Error != null)
					return null;
			}		
			totalAllocationSource = totalAllocationSource.add(line.getAmtSource());
			lineID = line.get_ID();
		}	//	for all lines

		//		rounding correction
		if (payment != null && getC_Currency_ID() != as.getC_Currency_ID()	)		//	payment allocation in foreign currency
		{
			p_Error = createPaymentGainLoss (as, fact,  getPaymentAcct(as, payment.get_ID()), payment,
					totalAllocationSource, paymentSelectAmt, lineID);
			if (p_Error != null)
				return null;		
		}
		
		if (getC_Currency_ID() != as.getC_Currency_ID())
		{
			p_Error = createInvoiceRounding (as, fact,  bpAcct);
			if (p_Error != null)
				return null;
		}
		
		// FR [ 1840016 ] Avoid usage of clearing accounts - subject to C_AcctSchema.IsPostIfClearingEqual
		if ( (!as.isPostIfClearingEqual()) && p_lines.length > 0 && (!isInterOrg)) {
			boolean allEquals = true;
			// more than one line (i.e. crossing one payment+ with a payment-, or an invoice against a credit memo)
			// verify if the sum of all facts is zero net
			FactLine[] factlines = fact.getLines();
			BigDecimal netBalance = Env.ZERO;
			FactLine prevFactLine = null;
			for (FactLine factLine : factlines) {
				netBalance = netBalance.add(factLine.getAmtSourceDr()).subtract(factLine.getAmtSourceCr());
				if (prevFactLine != null) {
					if (! equalFactLineIDs(prevFactLine, factLine)) {
						allEquals = false;
						break;
					}
				}
				prevFactLine = factLine;
			}
			if (netBalance.compareTo(Env.ZERO) == 0 && allEquals) {
				// delete the postings
				for (FactLine factline : factlines)
					fact.remove(factline);
			}
		}

		//	reset line info
		setC_BPartner_ID(0);
		//
		m_facts.add(fact);
		return m_facts;
	}   //  createFact

	/** Verify if the posting involves two or more organizations
	@return true if there are more than one org involved on the posting
	 */
	private boolean isInterOrg(MAcctSchema as) {
		MAcctSchemaElement elementorg = as.getAcctSchemaElement(MAcctSchemaElement.ELEMENTTYPE_Organization);
		if (elementorg == null || !elementorg.isBalanced()) {
			// no org element or not need to be balanced
			return false;
		}

		if (p_lines.length <= 0) {
			// no lines
			return false;
		}

		int startorg = p_lines[0].getAD_Org_ID();
		// validate if the allocation involves more than one org
		for (int i = 0; i < p_lines.length; i++) {
			DocLine_Allocation line = (DocLine_Allocation)p_lines[i];
			int orgpayment = startorg;
			MPayment payment = null;
			if (line.getC_Payment_ID() != 0) {
				payment = new MPayment (getCtx(), line.getC_Payment_ID(), getTrxName());
				orgpayment = payment.getAD_Org_ID();
			}
			int orginvoice = startorg;
			MInvoice invoice = null;
			if (line.getC_Invoice_ID() != 0) {
				invoice = new MInvoice (getCtx(), line.getC_Invoice_ID(), getTrxName());
				orginvoice = invoice.getAD_Org_ID();
			}
			int orgcashline = startorg;
			MCashLine cashline = null;
			if (line.getC_CashLine_ID() != 0) {
				cashline = new MCashLine (getCtx(), line.getC_CashLine_ID(), getTrxName());
				orgcashline = cashline.getAD_Org_ID();
			}
			int orgorder = startorg;
			MOrder order = null;
			if (line.getC_Order_ID() != 0) {
				order = new MOrder (getCtx(), line.getC_Order_ID(), getTrxName());
				orgorder = order.getAD_Org_ID();
			}

			if (   line.getAD_Org_ID() != startorg
				|| orgpayment != startorg
				|| orginvoice != startorg
				|| orgcashline != startorg
				|| orgorder != startorg)
				return true;
		}

		return false;
	}

	/**
	 * Compare the dimension ID's from two factlines
	 * @param allEquals
	 * @param prevFactLine
	 * @param factLine
	 * @return boolean indicating if both dimension ID's are equal
	 */
	private boolean equalFactLineIDs(FactLine prevFactLine, FactLine factLine) {
		return (factLine.getA_Asset_ID() == prevFactLine.getA_Asset_ID()
				&& factLine.getAccount_ID() == prevFactLine.getAccount_ID()
				&& factLine.getAD_Client_ID() == prevFactLine.getAD_Client_ID()
				&& factLine.getAD_Org_ID() == prevFactLine.getAD_Org_ID()
				&& factLine.getAD_OrgTrx_ID() == prevFactLine.getAD_OrgTrx_ID()
				&& factLine.getC_AcctSchema_ID() == prevFactLine.getC_AcctSchema_ID()
				&& factLine.getC_Activity_ID() == prevFactLine.getC_Activity_ID()
				&& factLine.getC_BPartner_ID() == prevFactLine.getC_BPartner_ID()
				&& factLine.getC_Campaign_ID() == prevFactLine.getC_Campaign_ID()
				&& factLine.getC_Currency_ID() == prevFactLine.getC_Currency_ID()
				&& factLine.getC_LocFrom_ID() == prevFactLine.getC_LocFrom_ID()
				&& factLine.getC_LocTo_ID() == prevFactLine.getC_LocTo_ID()
				&& factLine.getC_Period_ID() == prevFactLine.getC_Period_ID()
				&& factLine.getC_Project_ID() == prevFactLine.getC_Project_ID()
				&& factLine.getC_ProjectPhase_ID() == prevFactLine.getC_ProjectPhase_ID()
				&& factLine.getC_ProjectTask_ID() == prevFactLine.getC_ProjectTask_ID()
				&& factLine.getC_SalesRegion_ID() == prevFactLine.getC_SalesRegion_ID()
				&& factLine.getC_SubAcct_ID() == prevFactLine.getC_SubAcct_ID()
				&& factLine.getC_Tax_ID() == prevFactLine.getC_Tax_ID()
				&& factLine.getC_UOM_ID() == prevFactLine.getC_UOM_ID()
				&& factLine.getGL_Budget_ID() == prevFactLine.getGL_Budget_ID()
				&& factLine.getGL_Category_ID() == prevFactLine.getGL_Category_ID()
				&& factLine.getM_Locator_ID() == prevFactLine.getM_Locator_ID()
				&& factLine.getM_Product_ID() == prevFactLine.getM_Product_ID()
				&& factLine.getUserElement1_ID() == prevFactLine.getUserElement1_ID()
				&& factLine.getUserElement2_ID() == prevFactLine.getUserElement2_ID()
				&& factLine.getUser1_ID() == prevFactLine.getUser1_ID()
				&& factLine.getUser2_ID() == prevFactLine.getUser2_ID());
	}

	/**
	 * 	Create Cash Based Acct
	 * 	@param as accounting schema
	 *	@param fact fact
	 *	@param invoice invoice
	 *	@param allocationSource allocation amount (incl discount, writeoff)
	 *	@return Accounted Amt
	 */
	private BigDecimal createCashBasedAcct (MAcctSchema as, Fact fact, MInvoice invoice,
		BigDecimal allocationSource)
	{
		BigDecimal allocationAccounted = Env.ZERO;
		//	Multiplier
		double percent = invoice.getGrandTotal().doubleValue() / allocationSource.doubleValue();
		if (percent > 0.99 && percent < 1.01)
			percent = 1.0;
		if (log.isLoggable(Level.CONFIG)) log.config("Multiplier=" + percent + " - GrandTotal=" + invoice.getGrandTotal()
			+ " - Allocation Source=" + allocationSource);

		//	Get Invoice Postings
		Doc_Invoice docInvoice = (Doc_Invoice)Doc.get(as,
			MInvoice.Table_ID, invoice.getC_Invoice_ID(), getTrxName());
		docInvoice.loadDocumentDetails();
		allocationAccounted = docInvoice.createFactCash(as, fact, BigDecimal.valueOf(percent));
		if (log.isLoggable(Level.CONFIG)) log.config("Allocation Accounted=" + allocationAccounted);

		//	Cash Based Commitment Release
		if (as.isCreatePOCommitment() && !invoice.isSOTrx())
		{
			MInvoiceLine[] lines = invoice.getLines();
			for (int i = 0; i < lines.length; i++)
			{
				Fact factC = Doc_Order.getCommitmentRelease(as, this,
					lines[i].getQtyInvoiced(), lines[i].getC_InvoiceLine_ID(), BigDecimal.valueOf(percent));
				if (factC == null)
					return null;
				m_facts.add(factC);
			}
		}	//	Commitment

		return allocationAccounted;
	}	//	createCashBasedAcct


	/**
	 * 	Get Payment (Unallocated Payment or Payment Selection) Acct of Bank Account
	 *	@param as accounting schema
	 *	@param C_Payment_ID payment
	 *	@return acct
	 */
	private MAccount getPaymentAcct (MAcctSchema as, int C_Payment_ID)
	{
		setC_BankAccount_ID(0);
		//	Doc.ACCTTYPE_UnallocatedCash (AR) or C_Prepayment
		//	or Doc.ACCTTYPE_PaymentSelect (AP) or V_Prepayment
		int accountType = Doc.ACCTTYPE_UnallocatedCash;
		//
		String sql = "SELECT p.C_BankAccount_ID, d.DocBaseType, p.IsReceipt, p.IsPrepayment "
				+ "FROM C_Payment p INNER JOIN C_DocType d ON (p.C_DocType_ID=d.C_DocType_ID) "
				+ "WHERE C_Payment_ID=?";
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement (sql, getTrxName());
			pstmt.setInt (1, C_Payment_ID);
			rs = pstmt.executeQuery ();
			if (rs.next ())
			{
				setC_BankAccount_ID(rs.getInt(1));
				if (DOCTYPE_APPayment.equals(rs.getString(2)))
					accountType = Doc.ACCTTYPE_PaymentSelect;
				//	Prepayment
				if ("Y".equals(rs.getString(4)))		//	Prepayment
				{
					if ("Y".equals(rs.getString(3)))	//	Receipt
						accountType = Doc.ACCTTYPE_C_Prepayment;
					else
						accountType = Doc.ACCTTYPE_V_Prepayment;
				}
			}
 		}
		catch (Exception e)
		{
			throw new RuntimeException(e.getLocalizedMessage(), e);
		}
		finally
		{
			DB.close(rs, pstmt);
			rs = null; pstmt = null;
		}

		//
		if (getC_BankAccount_ID() <= 0)
		{
			log.log(Level.SEVERE, "NONE for C_Payment_ID=" + C_Payment_ID);
			return null;
		}
		return getAccount (accountType, as);
	}	//	getPaymentAcct

	/**
	 * 	Get Cash (Transfer) Acct of CashBook
	 *	@param as accounting schema
	 *	@param C_CashLine_ID
	 *	@return acct
	 */
	private MAccount getCashAcct (MAcctSchema as, int C_CashLine_ID)
	{
		String sql = "SELECT c.C_CashBook_ID "
				+ "FROM C_Cash c, C_CashLine cl "
				+ "WHERE c.C_Cash_ID=cl.C_Cash_ID AND cl.C_CashLine_ID=?";
		setC_CashBook_ID(DB.getSQLValue(null, sql, C_CashLine_ID));

		if (getC_CashBook_ID() <= 0)
		{
			log.log(Level.SEVERE, "NONE for C_CashLine_ID=" + C_CashLine_ID);
			return null;
		}
		return getAccount(Doc.ACCTTYPE_CashTransfer, as);
	}	//	getCashAcct


	/**************************************************************************
	 * 	Create Realized Gain & Loss.
	 * 	Compares the Accounted Amount of the Invoice to the
	 * 	Accounted Amount of the Allocation
	 *	@param as accounting schema
	 *	@param fact fact
	 *	@param acct account
	 *	@param invoice invoice
	 *	@param allocationSource source amt
	 *	@param allocationAccounted acct amt
	 *	@return Error Message or null if OK
	 */
	private String createRealizedGainLoss (DocLine line, MAcctSchema as, Fact fact, MAccount acct,
		MInvoice invoice, BigDecimal allocationSource, BigDecimal allocationAccounted)
	{
		BigDecimal invoiceSource = null;
		BigDecimal invoiceAccounted = null;
		//
		StringBuilder sql = new StringBuilder()
			.append("SELECT SUM(AmtSourceDr), SUM(AmtAcctDr), SUM(AmtSourceCr), SUM(AmtAcctCr)")
			.append(" FROM Fact_Acct ")
			.append("WHERE AD_Table_ID=? AND Record_ID=?")
			.append(" AND C_AcctSchema_ID=?")
			.append(" AND PostingType='A'");

		// For Invoice
		List<Object> valuesInv = DB.getSQLValueObjectsEx(getTrxName(), sql.toString(),
				MInvoice.Table_ID, invoice.getC_Invoice_ID(), as.getC_AcctSchema_ID());
		if (valuesInv != null) {
			if (invoice.isSOTrx()) {
				invoiceSource = (BigDecimal) valuesInv.get(0); // AmtSourceDr
				invoiceAccounted = (BigDecimal) valuesInv.get(1); // AmtAcctDr
			} else {
				invoiceSource = (BigDecimal) valuesInv.get(2); // AmtSourceCr
				invoiceAccounted = (BigDecimal) valuesInv.get(3); // AmtAcctCr
			}
		}
		
		// 	Requires that Invoice is Posted
		if (invoiceSource == null || invoiceAccounted == null)
			return "Gain/Loss - Invoice not posted yet";
		//
		StringBuilder description = new StringBuilder("Invoice=(").append(invoice.getC_Currency_ID()).append(")").append(invoiceSource).append("/").append(invoiceAccounted)
			.append(" - Allocation=(").append(getC_Currency_ID()).append(")").append(allocationSource).append("/").append(allocationAccounted);
		if (log.isLoggable(Level.FINE)) log.fine(description.toString());
		//	Allocation not Invoice Currency
		if (getC_Currency_ID() != invoice.getC_Currency_ID())
		{
			BigDecimal allocationSourceNew = MConversionRate.convert(getCtx(),
				allocationSource, getC_Currency_ID(),
				invoice.getC_Currency_ID(), getDateAcct(),
				invoice.getC_ConversionType_ID(), invoice.getAD_Client_ID(), invoice.getAD_Org_ID());
			if (allocationSourceNew == null)
				return "Gain/Loss - No Conversion from Allocation->Invoice";
			StringBuilder d2 = new StringBuilder("Allocation=(").append(getC_Currency_ID()).append(")").append(allocationSource)
				.append("->(").append(invoice.getC_Currency_ID()).append(")").append(allocationSourceNew);
			if (log.isLoggable(Level.FINE)) log.fine(d2.toString());
			description.append(" - ").append(d2);
			allocationSource = allocationSourceNew;
		}

		BigDecimal acctDifference = null;	//	gain is negative
		//reversal entry
		if (allocationSource.signum() > 0 )
		{
			acctDifference = invoiceAccounted.subtract(allocationAccounted.abs());
		}
		//	Full Payment in currency
		if (allocationSource.compareTo(invoiceSource) == 0)
		{
			acctDifference = invoiceAccounted.subtract(allocationAccounted.abs());	//	gain is negative

			StringBuilder d2 = new StringBuilder("(full) = ").append(acctDifference);
			if (log.isLoggable(Level.FINE)) log.fine(d2.toString());
			description.append(" - ").append(d2);
		}
		else	//	partial or MC
		{
			//	percent of total payment
			double multiplier = allocationSource.doubleValue() / invoiceSource.doubleValue();
			//	Reduce Orig Invoice Accounted
			invoiceAccounted = invoiceAccounted.multiply(BigDecimal.valueOf(multiplier));
			//	Difference based on percentage of Orig Invoice
			acctDifference = invoiceAccounted.subtract(allocationAccounted);	//	gain is negative
			//	ignore Tolerance
			if (acctDifference.abs().compareTo(TOLERANCE) < 0)
				acctDifference = Env.ZERO;
			//	Round
			int precision = as.getStdPrecision();
			if (acctDifference.scale() > precision)
				acctDifference = acctDifference.setScale(precision, RoundingMode.HALF_UP);
			StringBuilder d2 = new StringBuilder("(partial) = ").append(acctDifference).append(" - Multiplier=").append(multiplier);
			if (log.isLoggable(Level.FINE)) log.fine(d2.toString());
			description.append(" - ").append(d2);
		}

		if (acctDifference.signum() == 0)
		{
			log.fine("No Difference");
			return null;
		}

		MAccount gain = MAccount.get (as.getCtx(), as.getAcctSchemaDefault().getRealizedGain_Acct());
		MAccount loss = MAccount.get (as.getCtx(), as.getAcctSchemaDefault().getRealizedLoss_Acct());
		if (invoice.isCreditMemo() || invoice.getReversal_ID() > 0)
			cmGainLossAmt = cmGainLossAmt.add(acctDifference);
		else 
			gainLossAmt = gainLossAmt.add(acctDifference);
		//

		if (invoice.isSOTrx())
		{
			FactLine fl = fact.createLine (line, loss, gain, as.getC_Currency_ID(), acctDifference);
			fl.setDescription(description.toString());
			fl = fact.createLine (line, acct, as.getC_Currency_ID(), acctDifference.negate());
			gainLossFactList.add(fl);
		}
		else
		{
			FactLine fl = fact.createLine (line, acct,
				as.getC_Currency_ID(), acctDifference);
			gainLossFactList.add(fl);
			fl = fact.createLine (line, loss, gain,
				as.getC_Currency_ID(), acctDifference.negate());
			fl.setDescription(description.toString());

		}
		return null;
	}	//	createRealizedGainLoss


	/**************************************************************************
	 * 	Create Tax Correction.
	 * 	Requirement: Adjust the tax amount, if you did not receive the full
	 * 	amount of the invoice (payment discount, write-off).
	 * 	Applies to many countries with VAT.
	 * 	Example:
	 * 		Invoice:	Net $100 + Tax1 $15 + Tax2 $5 = Total $120
	 * 		Payment:	$115 (i.e. $5 underpayment)
	 * 		Tax Adjustment = Tax1 = 0.63 (15/120*5) Tax2 = 0.21 (5/120/5)
	 *
	 * 	@param as accounting schema
	 * 	@param fact fact
	 * 	@param line Allocation line
	 *	@param DiscountAccount discount acct
	 *	@param WriteOffAccoint write off acct
	 *	@return true if created
	 */
	private boolean createTaxCorrection (MAcctSchema as, Fact fact,
		DocLine_Allocation line,
		MAccount DiscountAccount, MAccount WriteOffAccoint, boolean isSOTrx)
	{
		if (log.isLoggable(Level.INFO)) log.info (line.toString());
		BigDecimal discount = Env.ZERO;
		if (as.isTaxCorrectionDiscount())
			discount = line.getDiscountAmt();
		BigDecimal writeOff = Env.ZERO;
		if (as.isTaxCorrectionWriteOff())
			writeOff = line.getWriteOffAmt();

		Doc_AllocationTax tax = new Doc_AllocationTax (
			DiscountAccount, discount, 	WriteOffAccoint, writeOff, isSOTrx);

		//	Get Source Amounts with account
		String sql = "SELECT * "
				+ "FROM Fact_Acct "
				+ "WHERE AD_Table_ID=? AND Record_ID=?"	//	Invoice
				+ " AND C_AcctSchema_ID=?"
				+ " AND Line_ID IS NULL";	//	header lines like tax or total
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		try
		{
			pstmt = DB.prepareStatement(sql, getTrxName());
			pstmt.setInt(1, MInvoice.Table_ID);
			pstmt.setInt(2, line.getC_Invoice_ID());
			pstmt.setInt(3, as.getC_AcctSchema_ID());
			rs = pstmt.executeQuery();
			while (rs.next())
				tax.addInvoiceFact (new MFactAcct(getCtx(), rs, fact.get_TrxName()));
		}
		catch (Exception e)
		{
			throw new RuntimeException(e.getLocalizedMessage(), e);
		}
		finally {
			DB.close(rs, pstmt);
			rs = null; pstmt = null;
		}
		//	Invoice Not posted
		if (tax.getLineCount() == 0)
		{
			log.warning ("Invoice not posted yet - " + line);
			return false;
		}
		//	size = 1 if no tax
		if (tax.getLineCount() < 2)
			return true;
		return tax.createEntries (as, fact, line);

	}	//	createTaxCorrection

	/**************************************************************************
	 * 	Create Rounding Correction.
	 * 	Compares the Accounted Amount of the Payment to the
	 * 	Accounted Amount of the Allocation
	 *	@param as accounting schema
	 *	@param fact fact
	 *	@param acct account
	 *	@param payment payment
	 *	@param paymentSource source amt
	 *	@param paymentAccounted acct amt
	 *	@return Error Message or null if OK
	 */
	private String createPaymentGainLoss (MAcctSchema as, Fact fact, MAccount acct,
		MPayment payment, BigDecimal allocationSource, BigDecimal totalAllocationAccounted, int lineID)
	{
		BigDecimal paymentSource = null;
		BigDecimal paymentAccounted = null;
		//
		StringBuilder sql = new StringBuilder()
			.append("SELECT SUM(AmtSourceDr), SUM(AmtAcctDr), SUM(AmtSourceCr), SUM(AmtAcctCr)")
			.append(" FROM Fact_Acct ")
			.append("WHERE AD_Table_ID=? AND Record_ID=?")
			.append(" AND C_AcctSchema_ID=?")
			.append(" AND Account_ID = ? ")
			.append(" AND PostingType='A'");

		// For Payment
		List<Object> valuesPay = DB.getSQLValueObjectsEx(getTrxName(), sql.toString(),
				MPayment.Table_ID, payment.getC_Payment_ID(), as.getC_AcctSchema_ID(), acct.getAccount_ID());
		if (valuesPay != null) {
			if (payment.isReceipt()) {
				paymentSource = (BigDecimal) valuesPay.get(2); // AmtSourceCr
				paymentAccounted = (BigDecimal) valuesPay.get(3); // AmtAcctCr
			} else {
				paymentSource = (BigDecimal) valuesPay.get(0); // AmtSourceDr
				paymentAccounted = (BigDecimal) valuesPay.get(1); // AmtAcctDr
			}
		}
		
		// 	Requires that Allocation is Posted
		if (paymentSource == null || paymentAccounted == null)
			return null; //"Gain/Loss - Payment not posted yet";
		//
		StringBuilder description = new StringBuilder("Payment=(").append(payment.getC_Currency_ID()).append(")").append(paymentSource).append("/").append(paymentAccounted)
			.append(" - Allocation=(").append(getC_Currency_ID()).append(")").append(allocationSource).append("/").append(totalAllocationAccounted);
		if (log.isLoggable(Level.FINE)) log.fine(description.toString());
		
		boolean isSameSourceDiffPeriod = false;
		BigDecimal acctDifference = null;	//	gain is negative
		//	Full Payment in currency
		if (allocationSource.abs().compareTo(paymentSource.abs()) == 0)
		{
			acctDifference = totalAllocationAccounted.subtract(paymentAccounted.abs());	//	gain is negative
			StringBuilder d2 = new StringBuilder("(full) = ").append(acctDifference);
			if (log.isLoggable(Level.FINE)) log.fine(d2.toString());
			description.append(" - ").append(d2);
			
			//	Different period
			if (MPeriod.getC_Period_ID(getCtx(), payment.getDateAcct(), payment.getAD_Org_ID()) != 
					MPeriod.getC_Period_ID(getCtx(), getDateAcct(), getAD_Org_ID())) 
			{
				BigDecimal allocationAccounted0 = MConversionRate.convert(getCtx(),
						allocationSource, getC_Currency_ID(),
						as.getC_Currency_ID(), payment.getDateAcct(),
						payment.getC_ConversionType_ID(), payment.getAD_Client_ID(), payment.getAD_Org_ID());
				BigDecimal paymentAccounted0 = MConversionRate.convert(getCtx(),
						paymentSource, getC_Currency_ID(),
						as.getC_Currency_ID(), getDateAcct(),
						getC_ConversionType_ID(), getAD_Client_ID(), getAD_Org_ID());
				isSameSourceDiffPeriod = allocationAccounted0.abs().compareTo(paymentAccounted.abs()) == 0 &&
						paymentAccounted0.abs().compareTo(totalAllocationAccounted.abs()) == 0;
			}
		}

		if (acctDifference == null || acctDifference.signum() == 0)
		{
			log.fine("No Difference");
			return null;
		}

		MAccount gain = MAccount.get (as.getCtx(), as.getAcctSchemaDefault().getRealizedGain_Acct());
		MAccount loss = MAccount.get (as.getCtx(), as.getAcctSchemaDefault().getRealizedLoss_Acct());
		//
		if (payment.isReceipt())
		{
			FactLine fl = fact.createLine (null, acct,as.getC_Currency_ID(), acctDifference.negate());
			fl.setDescription(description.toString());
			fl.setLine_ID(lineID);
			if (!fact.isAcctBalanced())
			{
				if (!isSameSourceDiffPeriod && as.isCurrencyBalancing() && as.getC_Currency_ID() != payment.getC_Currency_ID()  )
				{
					fact.createLine (null, as.getCurrencyBalancing_Acct(),as.getC_Currency_ID(), acctDifference);
				} else
				{
					fl = fact.createLine (null, loss, gain,as.getC_Currency_ID(), acctDifference);
	
				}
			}
		}
		else
		{
			FactLine fl = fact.createLine (null, acct,as.getC_Currency_ID(), acctDifference);
			fl.setDescription(description.toString());
			fl.setLine_ID(lineID);
			if (!fact.isAcctBalanced())
			{
				if (!isSameSourceDiffPeriod && as.isCurrencyBalancing() && as.getC_Currency_ID() != payment.getC_Currency_ID()  )
				{
					fact.createLine (null, as.getCurrencyBalancing_Acct(),as.getC_Currency_ID(), acctDifference.negate());
				} else {
					fact.createLine (null, loss, gain, as.getC_Currency_ID(), acctDifference.negate());	
				}
			}
			
		}
		return null;
	}

	/**************************************************************************
	 * 	Create Rounding Correction.
	 * 	Compares the Accounted Amount of the AR/AP Invoice to the
	 * 	Accounted Amount of the AR/AP Allocation
	 *	@param as accounting schema
	 *	@param fact fact
	 *	@param bpAcct account
	 *	@param payment payment
	 *	@return Error Message or null if OK
	 */
	private String createInvoiceRounding(MAcctSchema as, Fact fact, MAccount bpAcct) {
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		// Invoice AR/AP
		BigDecimal totalInvoiceSource = BigDecimal.ZERO;
		BigDecimal totalInvoiceAccounted = BigDecimal.ZERO;
		boolean isCMReversal =false ; 
		MInvoice invoice = null;
		MPayment payment = null;
		ArrayList<MInvoice> invList = new ArrayList<MInvoice>();
		for (int i = 0; i < p_lines.length; i++)
		{
			DocLine_Allocation line = (DocLine_Allocation)p_lines[i];			
			if (line.getC_Invoice_ID() != 0)
				invoice = new MInvoice (getCtx(), line.getC_Invoice_ID(), getTrxName());		
			if (line.getC_Payment_ID() != 0)
				payment = new MPayment (getCtx(), line.getC_Payment_ID(), getTrxName());

			if (invoice != null )
			{
				boolean isDebit = false;
				// to cater for invoice reverse-accrual.
				if (invoice.isSOTrx() && !invoice.isCreditMemo())
					isDebit = true;
				else if  (!invoice.isSOTrx() && invoice.isCreditMemo() && invoice.getReversal_ID() > 0 )
					isDebit = true;
				else if (invoice.isSOTrx() && invoice.isCreditMemo() && invoice.getReversal_ID() == 0)
					isDebit = true;
				//
				StringBuilder sql = new StringBuilder("SELECT ")
					.append((isDebit ) 
						? "SUM(AmtSourceDr), SUM(AmtAcctDr)"	//	so
						: "SUM(AmtSourceCr), SUM(AmtAcctCr)")	//	po
					.append(" FROM Fact_Acct ")
					.append("WHERE AD_Table_ID=? AND Record_ID=?")	//	Invoice
					.append(" AND C_AcctSchema_ID=?")
					.append(" AND PostingType='A'")
					.append(" AND Account_ID= ? ");
				pstmt = null;
				rs = null;
				try
				{
					pstmt = DB.prepareStatement(sql.toString(), getTrxName());
					pstmt.setInt(1, MInvoice.Table_ID);
					pstmt.setInt(2, invoice.getC_Invoice_ID());
					pstmt.setInt(3, as.getC_AcctSchema_ID());
					pstmt.setInt(4, bpAcct.getAccount_ID());
					rs = pstmt.executeQuery();
					if (rs.next())
					{
						BigDecimal invoiceSource = rs.getBigDecimal(1);
						BigDecimal invoiceAccounted = rs.getBigDecimal(2);						
						if ( !invList.contains(invoice))
						{
							totalInvoiceSource =totalInvoiceSource.add(invoiceSource);
							totalInvoiceAccounted =totalInvoiceAccounted.add(invoiceAccounted);
						} 

				
						invList.add(invoice);
					}					
				}
				catch (Exception e)
				{
					throw new RuntimeException(e.getLocalizedMessage(), e);
				}
				finally {
					DB.close(rs, pstmt);
					rs = null; pstmt = null;
				}	
			}
		}
		BigDecimal allocInvoiceSource = BigDecimal.ZERO;
		BigDecimal allocInvoiceAccounted = BigDecimal.ZERO;
		MAllocationLine allocationLine = null;
		FactLine[] factlines = fact.getLines();
		boolean isExcludeCMGainLoss = false;
		for (FactLine factLine : factlines) {		
			if (bpAcct != null) { 		
				if (factLine.getAccount_ID() == bpAcct.getAccount_ID() )
				{
					if (factLine.getLine_ID() != 0 ) 
						allocationLine = new MAllocationLine(getCtx(), factLine.getLine_ID(), getTrxName());
					
					if (allocationLine != null)
						invoice = allocationLine.getInvoice();

					if (invoice.isSOTrx())
					{
						if (factLine.getC_Currency_ID() != as.getC_Currency_ID())							
							allocInvoiceSource = allocInvoiceSource.add(factLine.getAmtSourceCr());
						if (!gainLossFactList.contains(factLine) && !invoice.isCreditMemo())
							allocInvoiceAccounted = allocInvoiceAccounted.add(factLine.getAmtAcctCr());
						
						if (!gainLossFactList.contains(factLine)  && invoice.isCreditMemo() && invoice.getReversal_ID() > 0 ) {
							allocInvoiceAccounted = allocInvoiceAccounted.add(factLine.getAmtAcctDr());
							if (!invoice.getDateAcct().equals(getDateAcct())) 
								 isCMReversal =true;
						}
						if (invoice!=null)
						{
							if (invoice.isCreditMemo()  || invoice.getReversal_ID() > 0 ) {
								allocInvoiceAccounted = allocInvoiceAccounted.add(cmGainLossAmt.abs());
								cmGainLossAmt = Env.ZERO;
							}
							if (gainLossFactList.contains(factLine)) {
								isExcludeCMGainLoss = true;
							}
						}
						
						if (payment != null && payment.getReversal_ID() > 0 && !gainLossFactList.contains(factLine))
						{
							allocInvoiceSource = allocInvoiceSource.add(factLine.getAmtSourceDr());
							allocInvoiceAccounted = allocInvoiceAccounted.add(factLine.getAmtAcctDr());
						}

					} else
					{
						if (as.getC_Currency_ID() != factLine.getC_Currency_ID())
							allocInvoiceSource = allocInvoiceSource.add(factLine.getAmtSourceDr());

						if (!gainLossFactList.contains(factLine) && !invoice.isCreditMemo()) 
							allocInvoiceAccounted = allocInvoiceAccounted.add(factLine.getAmtAcctDr());

						if (!gainLossFactList.contains(factLine)  && invoice.isCreditMemo() && invoice.getReversal_ID() > 0 ) {
							allocInvoiceAccounted = allocInvoiceAccounted.add(factLine.getAmtAcctCr());
							// this is to cater for reverse-accrual.
							if (!invoice.getDateAcct().equals(getDateAcct())) 
								isCMReversal =true;
						}

						if (invoice!=null )
						{
							if (invoice.isCreditMemo() || invoice.getReversal_ID() > 0 ) {
								allocInvoiceAccounted = allocInvoiceAccounted.add(cmGainLossAmt.abs());
								cmGainLossAmt = Env.ZERO;
							}
							if (gainLossFactList.contains(factLine)) {
								isExcludeCMGainLoss = true;
							}
							
						}

					}				

				}
			}
		}


		BigDecimal acctDifference = null;	//	gain is negative
		//
		StringBuilder description = new StringBuilder("Invoice=(").append(getC_Currency_ID()).append(")").append(allocInvoiceSource).append("/").append(allocInvoiceAccounted);
		if (log.isLoggable(Level.FINE)) log.fine(description.toString());
		boolean isBPartnerAdjust = true;
		if (allocInvoiceSource.abs().compareTo(totalInvoiceSource.abs()) == 0)
		{
			if (isExcludeCMGainLoss)
				allocInvoiceAccounted = allocInvoiceAccounted.add(cmGainLossAmt);
			if (payment != null && payment.getReversal_ID() > 0 )
				allocInvoiceAccounted = allocInvoiceAccounted.subtract(gainLossAmt);
			else
				allocInvoiceAccounted = allocInvoiceAccounted.add(gainLossAmt);
			if (isCMReversal)
				acctDifference = totalInvoiceAccounted.subtract(allocInvoiceAccounted.abs());	
			else
				acctDifference = allocInvoiceAccounted.subtract(totalInvoiceAccounted.abs());	//	gain is positive for receipt
			
			StringBuilder d2 = new StringBuilder("(full) = ").append(acctDifference);
			if (log.isLoggable(Level.FINE)) log.fine(d2.toString());
			description.append(" - ").append(d2);
		} else{
			MAllocationHdr[] allocations = MAllocationHdr.getOfInvoice(getCtx(), invoice.get_ID(), getTrxName());
			for (MAllocationHdr alloc : allocations)
			{
				StringBuilder sql = new StringBuilder("SELECT ")
						.append(invoice.isSOTrx() 
							? "SUM(AmtSourceCr), SUM(AmtAcctCr), SUM(AmtAcctDr)"	//	so
							: "SUM(AmtSourceDr), SUM(AmtAcctDr), SUM(AmtAcctCr)")	//	po
						.append(" FROM Fact_Acct ")
						.append("WHERE AD_Table_ID=? AND Record_ID=?")	//	allocation
						.append(" AND C_AcctSchema_ID=?")
						.append(" AND PostingType='A'")
						.append(" AND Account_ID= ? ");
					pstmt = null;
					rs = null;
					try
					{
						pstmt = DB.prepareStatement(sql.toString(), getTrxName());
						pstmt.setInt(1, MAllocationHdr.Table_ID);
						pstmt.setInt(2, alloc.get_ID());
						pstmt.setInt(3, as.getC_AcctSchema_ID());
						pstmt.setInt(4, bpAcct.getAccount_ID());
						rs = pstmt.executeQuery();
						if (rs.next())
						{
							BigDecimal allocateSource = rs.getBigDecimal(1);
							BigDecimal allocateAccounted = rs.getBigDecimal(2);
							BigDecimal allocateCredit = rs.getBigDecimal(3);
							allocInvoiceSource =allocInvoiceSource.add(allocateSource != null ? allocateSource: BigDecimal.ZERO);
							allocInvoiceAccounted =allocInvoiceAccounted.add(allocateAccounted != null ? allocateAccounted : BigDecimal.ZERO);
							allocInvoiceAccounted= allocInvoiceAccounted.subtract(allocateCredit != null ? allocateCredit : BigDecimal.ZERO);
						}
						
					}
					catch (Exception e)
					{
						throw new RuntimeException(e.getLocalizedMessage(), e);
					}
					finally {
						DB.close(rs, pstmt);
						rs = null; pstmt = null;
				}
			}
			double multiplier = allocInvoiceSource.doubleValue() / totalInvoiceSource.doubleValue();

			//	Reduce Orig Invoice Accounted
			BigDecimal reduceOrigAccounted = totalInvoiceAccounted.multiply(BigDecimal.valueOf(multiplier));
			if (reduceOrigAccounted.compareTo(totalInvoiceAccounted) < 0 )
				totalInvoiceAccounted = reduceOrigAccounted;
			if (isExcludeCMGainLoss)
				allocInvoiceAccounted = allocInvoiceAccounted.add(cmGainLossAmt);
			
			allocInvoiceAccounted = allocInvoiceAccounted.add(gainLossAmt);

			//	Difference based on percentage of Orig Invoice
			acctDifference = allocInvoiceAccounted.subtract(totalInvoiceAccounted);	
			//	ignore Tolerance
			if (acctDifference.abs().compareTo(BigDecimal.valueOf(0.01)) < 0)
				acctDifference = Env.ZERO;

			//	Round
			int precision = as.getStdPrecision();
			if (acctDifference.scale() > precision)
				acctDifference = acctDifference.setScale(precision, RoundingMode.HALF_UP);
			StringBuilder d2 = new StringBuilder("(partial) = ").append(acctDifference).append(" - Multiplier=").append(multiplier);
			if (log.isLoggable(Level.FINE)) log.fine(d2.toString());
			description.append(" - ").append(d2);
		}

		if (acctDifference.signum() == 0)
		{
			log.fine("No Difference");
			return null;
		}
			
		MAccount gain = MAccount.get (as.getCtx(), as.getAcctSchemaDefault().getRealizedGain_Acct());
		MAccount loss = MAccount.get (as.getCtx(), as.getAcctSchemaDefault().getRealizedLoss_Acct());
		//
		if (acctDifference.abs().compareTo(TOLERANCE) <= 0)
		{
			if (invoice.isSOTrx())
			{
				FactLine fl = null;
					if (!isBPartnerAdjust)
						fl = fact.createLine (null, as.getCurrencyBalancing_Acct(),as.getC_Currency_ID(), acctDifference);
					else
						fl = fact.createLine (null, bpAcct,as.getC_Currency_ID(), acctDifference);
					fl.setDescription(description.toString());
	
					if (!fact.isAcctBalanced())
					{
						if (as.isCurrencyBalancing() && as.getC_Currency_ID() != invoice.getC_Currency_ID()  )
						{
							fact.createLine (null, as.getCurrencyBalancing_Acct(),as.getC_Currency_ID(), acctDifference.negate());
						} else
						{
							fl = fact.createLine (null, loss, gain,as.getC_Currency_ID(), acctDifference.negate());
	
						}
					}
				}else
				{
					FactLine fl = null;
					if (!isBPartnerAdjust)
						fl = fact.createLine (null, as.getCurrencyBalancing_Acct(),as.getC_Currency_ID(), acctDifference.negate());
					else
						fl = fact.createLine (null, bpAcct,as.getC_Currency_ID(), acctDifference.negate());
	
					fl.setDescription(description.toString());
					if (!fact.isAcctBalanced())
					{
						if (as.isCurrencyBalancing() && as.getC_Currency_ID() != invoice.getC_Currency_ID()  )
						{
							fact.createLine (null, as.getCurrencyBalancing_Acct(),as.getC_Currency_ID(), acctDifference);
						} else {
							fact.createLine (null, loss, gain, as.getC_Currency_ID(), acctDifference);	
						}
					}
				}
			}
		return null;		
		
	}	//	createInvoiceRounding
} //  FTUDoc_Allocation

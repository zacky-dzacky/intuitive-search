-- =====================================================================
-- Seed data: ~50 app features + demo customer data.
--
-- Adding feature #51 is exactly one INSERT here (or one API/admin write).
-- `embedding` is left NULL — embedding-service/precompute_embeddings.py
-- backfills it offline.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Features that ACCEPT PARAMETERS (has_params = true)
--    `slots[].resolver` selects a Stage-3 resolver strategy:
--      payee | account | currency | amount | date | period | phone | none
-- ---------------------------------------------------------------------
INSERT INTO features (feature_id, display_name, description, category, route, keywords, aliases, has_params, slots) VALUES
('transfer',
 'Transfer Money',
 'Send money to a saved payee, another bank account, or between your own accounts.',
 'payments', '/payments/transfer',
 ARRAY['transfer','send','money','payment','remit','wire','pay someone','move funds'],
 ARRAY['trf','tf','xfer','trx','kirim','transf'],
 TRUE,
 '[
   {"name":"recipient","type":"string","required":true,"resolver":"payee","description":"Who the money is going to: a saved payee nickname, a person name, or an account number."},
   {"name":"amount","type":"number","required":true,"resolver":"amount","description":"Numeric amount to transfer, without currency symbols or thousand separators."},
   {"name":"currency","type":"string","required":false,"resolver":"currency","description":"ISO-4217 currency code such as USD, EUR, SGD, IDR."},
   {"name":"source_account","type":"string","required":false,"resolver":"account","description":"The account to debit, if the user named one."},
   {"name":"note","type":"string","required":false,"resolver":"none","description":"Free-text remark or reference for the transfer."}
 ]'::jsonb),

('schedule_transfer',
 'Schedule a Transfer',
 'Set up a future-dated or recurring transfer to a payee.',
 'payments', '/payments/transfer/schedule',
 ARRAY['schedule','future','recurring','standing order','repeat transfer','later'],
 ARRAY['sched trf','auto trf','standing'],
 TRUE,
 '[
   {"name":"recipient","type":"string","required":true,"resolver":"payee","description":"Who the scheduled money is going to."},
   {"name":"amount","type":"number","required":true,"resolver":"amount","description":"Numeric amount per occurrence."},
   {"name":"currency","type":"string","required":false,"resolver":"currency","description":"ISO-4217 currency code."},
   {"name":"start_date","type":"string","required":false,"resolver":"date","description":"ISO-8601 date (YYYY-MM-DD) of the first transfer."},
   {"name":"frequency","type":"string","required":false,"resolver":"none","enum":["once","weekly","monthly","quarterly","yearly"],"description":"How often the transfer repeats."}
 ]'::jsonb),

('pay_bill',
 'Pay Bills',
 'Pay electricity, water, internet, mobile postpaid, insurance and other billers.',
 'payments', '/payments/bills',
 ARRAY['bill','pay bill','biller','utility','electricity','water','internet','postpaid','invoice'],
 ARRAY['bills','byr','tagihan'],
 TRUE,
 '[
   {"name":"biller","type":"string","required":true,"resolver":"none","description":"Name of the biller or utility company."},
   {"name":"bill_number","type":"string","required":false,"resolver":"none","description":"Customer or bill reference number."},
   {"name":"amount","type":"number","required":false,"resolver":"amount","description":"Numeric amount to pay, if the user specified one."},
   {"name":"currency","type":"string","required":false,"resolver":"currency","description":"ISO-4217 currency code."},
   {"name":"source_account","type":"string","required":false,"resolver":"account","description":"The account to debit."}
 ]'::jsonb),

('download_e_statement',
 'Download e-Statement',
 'Download a monthly account statement as PDF.',
 'accounts', '/accounts/statements',
 ARRAY['statement','e-statement','download statement','monthly statement','account statement','pdf statement'],
 ARRAY['e-stmt','estmt','estatement','stmt','e statement'],
 TRUE,
 '[
   {"name":"account","type":"string","required":false,"resolver":"account","description":"Which account the statement is for."},
   {"name":"period","type":"string","required":false,"resolver":"period","description":"Statement period the user asked for, e.g. \"March\", \"last month\", \"2025-03\"."},
   {"name":"format","type":"string","required":false,"resolver":"none","enum":["pdf","csv"],"description":"Requested file format."}
 ]'::jsonb),

('topup_ewallet',
 'Top Up e-Wallet',
 'Top up a linked digital wallet balance.',
 'payments', '/payments/ewallet-topup',
 ARRAY['top up','topup','e-wallet','wallet','reload wallet','load balance'],
 ARRAY['tp','top-up','ewallet'],
 TRUE,
 '[
   {"name":"wallet","type":"string","required":true,"resolver":"none","description":"Name of the e-wallet provider."},
   {"name":"phone_number","type":"string","required":false,"resolver":"phone","description":"Wallet phone number in digits."},
   {"name":"amount","type":"number","required":true,"resolver":"amount","description":"Numeric top-up amount."},
   {"name":"currency","type":"string","required":false,"resolver":"currency","description":"ISO-4217 currency code."}
 ]'::jsonb),

('buy_airtime',
 'Buy Airtime / Prepaid Reload',
 'Buy mobile prepaid credit or a data package for a phone number.',
 'payments', '/payments/airtime',
 ARRAY['airtime','prepaid','pulsa','mobile credit','phone credit','data package','reload'],
 ARRAY['pulsa','credit'],
 TRUE,
 '[
   {"name":"phone_number","type":"string","required":true,"resolver":"phone","description":"Destination mobile number in digits."},
   {"name":"amount","type":"number","required":false,"resolver":"amount","description":"Numeric denomination to buy."},
   {"name":"provider","type":"string","required":false,"resolver":"none","description":"Mobile network operator."}
 ]'::jsonb),

('currency_exchange',
 'Currency Exchange',
 'Convert money between currencies held in your multi-currency accounts.',
 'accounts', '/accounts/fx',
 ARRAY['exchange','convert','foreign exchange','fx','currency conversion','buy currency'],
 ARRAY['fx','forex','tukar'],
 TRUE,
 '[
   {"name":"amount","type":"number","required":true,"resolver":"amount","description":"Numeric amount to convert."},
   {"name":"from_currency","type":"string","required":false,"resolver":"currency","description":"ISO-4217 code of the currency being sold."},
   {"name":"to_currency","type":"string","required":false,"resolver":"currency","description":"ISO-4217 code of the currency being bought."}
 ]'::jsonb),

('transaction_history',
 'Transaction History',
 'Search and filter past transactions on your accounts.',
 'accounts', '/accounts/transactions',
 ARRAY['history','transactions','activity','past payments','statement lines','mutasi','search transactions'],
 ARRAY['trx history','txn','history'],
 TRUE,
 '[
   {"name":"account","type":"string","required":false,"resolver":"account","description":"Which account to search."},
   {"name":"counterparty","type":"string","required":false,"resolver":"payee","description":"Name of the other party to filter on."},
   {"name":"min_amount","type":"number","required":false,"resolver":"amount","description":"Lower bound of the amount filter."},
   {"name":"max_amount","type":"number","required":false,"resolver":"amount","description":"Upper bound of the amount filter."},
   {"name":"from_date","type":"string","required":false,"resolver":"date","description":"ISO-8601 start date of the range."},
   {"name":"to_date","type":"string","required":false,"resolver":"date","description":"ISO-8601 end date of the range."}
 ]'::jsonb),

('open_time_deposit',
 'Open Time Deposit',
 'Place funds into a fixed-term deposit at a locked interest rate.',
 'investments', '/investments/time-deposit/new',
 ARRAY['time deposit','fixed deposit','term deposit','deposito','place deposit','lock savings'],
 ARRAY['td','fd','deposito'],
 TRUE,
 '[
   {"name":"amount","type":"number","required":true,"resolver":"amount","description":"Principal amount to place."},
   {"name":"currency","type":"string","required":false,"resolver":"currency","description":"ISO-4217 currency code."},
   {"name":"tenor_months","type":"number","required":false,"resolver":"amount","description":"Deposit term expressed in months."},
   {"name":"source_account","type":"string","required":false,"resolver":"account","description":"Account to debit the principal from."}
 ]'::jsonb),

('card_limit_change',
 'Change Card Limit',
 'Request a temporary or permanent credit or debit card limit change.',
 'cards', '/cards/limit',
 ARRAY['card limit','credit limit','increase limit','spending limit','raise limit'],
 ARRAY['cc limit','limit'],
 TRUE,
 '[
   {"name":"card","type":"string","required":false,"resolver":"none","description":"Which card, e.g. last four digits or card name."},
   {"name":"limit_amount","type":"number","required":true,"resolver":"amount","description":"Requested new limit as a number."},
   {"name":"currency","type":"string","required":false,"resolver":"currency","description":"ISO-4217 currency code."},
   {"name":"duration","type":"string","required":false,"resolver":"none","enum":["temporary","permanent"],"description":"Whether the change is temporary or permanent."}
 ]'::jsonb),

('add_payee',
 'Add New Payee',
 'Save a new beneficiary so future transfers are one tap.',
 'payments', '/payments/payees/new',
 ARRAY['add payee','new beneficiary','save recipient','register account','add recipient'],
 ARRAY['payee','beneficiary'],
 TRUE,
 '[
   {"name":"full_name","type":"string","required":false,"resolver":"none","description":"Legal name of the beneficiary."},
   {"name":"account_number","type":"string","required":false,"resolver":"none","description":"Beneficiary account number in digits."},
   {"name":"bank","type":"string","required":false,"resolver":"none","description":"Beneficiary bank name."},
   {"name":"nickname","type":"string","required":false,"resolver":"none","description":"Short nickname for the beneficiary."}
 ]'::jsonb),

('split_bill',
 'Split a Bill',
 'Request money from several people for a shared expense.',
 'payments', '/payments/split',
 ARRAY['split','split bill','request money','share cost','collect from friends'],
 ARRAY['splitbill','patungan'],
 TRUE,
 '[
   {"name":"total_amount","type":"number","required":false,"resolver":"amount","description":"Total bill amount to split."},
   {"name":"currency","type":"string","required":false,"resolver":"currency","description":"ISO-4217 currency code."},
   {"name":"participants","type":"string","required":false,"resolver":"payee","description":"People to split with."}
 ]'::jsonb);

-- ---------------------------------------------------------------------
-- 2. Navigation-only features (has_params = false)
--    Stage 2 can never fire for these — the pipeline stops at Stage 1.
-- ---------------------------------------------------------------------
INSERT INTO features (feature_id, display_name, description, category, route, keywords, aliases) VALUES
('check_balance','Check Balance','See the current available balance across all your accounts.','accounts','/accounts/balance',
  ARRAY['balance','available balance','how much money','account balance','saldo'],ARRAY['bal','saldo']),
('account_list','My Accounts','List every account you hold with the bank.','accounts','/accounts',
  ARRAY['accounts','my accounts','account list','portfolio'],ARRAY['acct','accts']),
('account_details','Account Details','View account number, branch, product type and interest rate.','accounts','/accounts/details',
  ARRAY['account details','account number','branch','product info','iban'],ARRAY['acct info']),
('settings_profile','Profile & Settings','Update your profile, contact details and app preferences.','settings','/settings/profile',
  ARRAY['settings','profile','preferences','my account settings','personal details'],ARRAY['setting','prefs','profil']),
('change_password','Change Password','Change your internet banking password.','security','/settings/security/password',
  ARRAY['password','change password','reset password','new password'],ARRAY['pwd','pass']),
('change_pin','Change PIN','Change your transaction or card PIN.','security','/settings/security/pin',
  ARRAY['pin','change pin','transaction pin','card pin'],ARRAY['pin']),
('biometric_login','Biometric Login','Enable or disable fingerprint and face login.','security','/settings/security/biometrics',
  ARRAY['biometric','fingerprint','face id','touch id','login method'],ARRAY['bio','faceid']),
('two_factor','Two-Factor Authentication','Manage OTP delivery and authenticator app settings.','security','/settings/security/2fa',
  ARRAY['two factor','2fa','otp','authenticator','security code'],ARRAY['2fa','otp','mfa']),
('block_card','Block or Freeze Card','Temporarily freeze or permanently block a lost card.','cards','/cards/block',
  ARRAY['block card','freeze card','lost card','stolen card','disable card'],ARRAY['blok','freeze']),
('unblock_card','Unblock Card','Reactivate a card you previously froze.','cards','/cards/unblock',
  ARRAY['unblock','unfreeze','reactivate card','enable card'],ARRAY['unblok']),
('card_list','My Cards','View all debit and credit cards linked to your profile.','cards','/cards',
  ARRAY['cards','my cards','debit card','credit card'],ARRAY['cc','card']),
('activate_card','Activate New Card','Activate a newly issued card.','cards','/cards/activate',
  ARRAY['activate card','new card','card activation','enable new card'],ARRAY['activate']),
('view_card_pin','View Card PIN','Securely reveal your card PIN.','cards','/cards/pin',
  ARRAY['card pin','view pin','show pin','reveal pin'],ARRAY['pin card']),
('credit_card_bill','Credit Card Bill','See your credit card statement balance and due date.','cards','/cards/statement',
  ARRAY['credit card bill','card statement','minimum payment','due date','outstanding'],ARRAY['cc bill','cc stmt']),
('apply_credit_card','Apply for a Credit Card','Browse and apply for a new credit card product.','cards','/cards/apply',
  ARRAY['apply card','new credit card','card application','get a card'],ARRAY['apply cc']),
('rewards_points','Rewards & Points','Check reward point balance and redeem rewards.','cards','/rewards',
  ARRAY['rewards','points','loyalty','redeem','cashback','miles'],ARRAY['poin','reward']),
('apply_loan','Apply for a Loan','Start a personal, auto or home loan application.','loans','/loans/apply',
  ARRAY['loan','apply loan','borrow','personal loan','mortgage','credit'],ARRAY['kta','pinjaman']),
('loan_status','Loan Status','Track the status of a submitted loan application.','loans','/loans/status',
  ARRAY['loan status','application status','approval status','track loan'],ARRAY['loan stat']),
('loan_repayment','Loan Repayment Schedule','View instalments, outstanding principal and payoff amount.','loans','/loans/repayment',
  ARRAY['repayment','instalment','installment','amortization','payoff','angsuran'],ARRAY['cicilan']),
('mutual_funds','Mutual Funds','Browse, buy and sell mutual fund products.','investments','/investments/funds',
  ARRAY['mutual fund','fund','investment','reksadana','unit trust','portfolio'],ARRAY['mf','reksa']),
('stock_trading','Stock Trading','Access the equities trading desk.','investments','/investments/stocks',
  ARRAY['stocks','shares','equities','trading','buy stock','saham'],ARRAY['saham','stock']),
('bonds','Bonds & Sukuk','Browse government and corporate bond offerings.','investments','/investments/bonds',
  ARRAY['bonds','sukuk','fixed income','government bond','obligasi'],ARRAY['obligasi']),
('insurance','Insurance Products','Browse life, health and travel insurance products.','insurance','/insurance',
  ARRAY['insurance','life insurance','health cover','travel insurance','asuransi','policy'],ARRAY['asuransi']),
('insurance_claim','File an Insurance Claim','Submit and track an insurance claim.','insurance','/insurance/claims',
  ARRAY['claim','file claim','insurance claim','reimbursement'],ARRAY['klaim']),
('qr_pay','Scan QR to Pay','Pay a merchant by scanning their QR code.','payments','/payments/qr',
  ARRAY['qr','scan','qr pay','merchant payment','scan to pay','qris'],ARRAY['qr','qris']),
('my_qr_code','My QR Code','Show your QR code so someone can pay you.','payments','/payments/qr/receive',
  ARRAY['my qr','receive money','show qr','get paid','request payment'],ARRAY['my qr']),
('scheduled_payments','Scheduled Payments','Review, edit or cancel upcoming scheduled payments.','payments','/payments/scheduled',
  ARRAY['scheduled','upcoming payments','standing instructions','autopay','recurring'],ARRAY['autopay','si']),
('payee_list','Saved Payees','Manage the beneficiaries you have saved.','payments','/payments/payees',
  ARRAY['payees','beneficiaries','saved recipients','contact list'],ARRAY['payees']),
('exchange_rates','Exchange Rates','See today''s buy and sell rates for supported currencies.','accounts','/rates/fx',
  ARRAY['rates','exchange rate','fx rate','currency rate','kurs'],ARRAY['kurs','rate']),
('interest_rates','Interest Rates','See current deposit and lending interest rates.','accounts','/rates/interest',
  ARRAY['interest rate','deposit rate','lending rate','bunga'],ARRAY['bunga']),
('branch_locator','Branch & ATM Locator','Find the nearest branch or ATM.','support','/locations',
  ARRAY['branch','atm','locator','nearest','find branch','opening hours'],ARRAY['atm','cabang']),
('contact_support','Contact Support','Reach the call centre, chat or email support.','support','/support/contact',
  ARRAY['support','help','contact','call centre','customer service','complaint'],ARRAY['cs','help']),
('live_chat','Live Chat','Open a chat session with a banking agent.','support','/support/chat',
  ARRAY['chat','live chat','talk to agent','message support'],ARRAY['chat']),
('dispute_transaction','Dispute a Transaction','Raise a dispute or report an unrecognised charge.','support','/support/dispute',
  ARRAY['dispute','chargeback','unauthorised','fraud','wrong charge','report transaction'],ARRAY['dispute','fraud']),
('report_fraud','Report Fraud','Report suspected fraud or a phishing attempt.','security','/security/report-fraud',
  ARRAY['fraud','scam','phishing','report fraud','suspicious activity'],ARRAY['scam','fraud']),
('notifications','Notification Settings','Choose which alerts you receive and how.','settings','/settings/notifications',
  ARRAY['notifications','alerts','push','sms alert','email alert'],ARRAY['notif','alerts']),
('language_settings','Language','Change the app display language.','settings','/settings/language',
  ARRAY['language','bahasa','locale','change language'],ARRAY['lang','bahasa']),
('theme_settings','Appearance','Switch between light and dark appearance.','settings','/settings/appearance',
  ARRAY['theme','dark mode','light mode','appearance','display'],ARRAY['dark','theme']),
('linked_devices','Linked Devices','Review and remove devices signed in to your account.','security','/settings/security/devices',
  ARRAY['devices','linked devices','sessions','logged in','remove device'],ARRAY['devices']),
('transaction_limits','Transaction Limits','View and adjust daily transfer and payment limits.','settings','/settings/limits',
  ARRAY['limits','daily limit','transfer limit','payment limit','max transfer'],ARRAY['limit']),
('tax_documents','Tax Documents','Download annual interest and tax certificates.','accounts','/accounts/tax-documents',
  ARRAY['tax','tax document','tax certificate','interest certificate','1099'],ARRAY['tax','pajak']),
('proof_of_transfer','Transfer Receipt','Download or share the receipt for a completed transfer.','payments','/payments/receipts',
  ARRAY['receipt','proof of transfer','transfer slip','bukti transfer','share receipt'],ARRAY['bukti','receipt']),
('close_account','Close an Account','Start the account closure process.','accounts','/accounts/close',
  ARRAY['close account','terminate account','delete account','tutup rekening'],ARRAY['close']),
('open_account','Open a New Account','Open an additional savings or current account.','accounts','/accounts/open',
  ARRAY['open account','new account','additional account','savings account','buka rekening'],ARRAY['open acct']),
('update_address','Update Address','Change the mailing or residential address on file.','settings','/settings/profile/address',
  ARRAY['address','update address','change address','mailing address','domicile'],ARRAY['alamat']),
('update_phone','Update Phone Number','Change the mobile number linked to your account.','settings','/settings/profile/phone',
  ARRAY['phone','mobile number','change number','update phone','contact number'],ARRAY['hp','phone']),
('update_email','Update Email','Change the email address linked to your account.','settings','/settings/profile/email',
  ARRAY['email','change email','update email','email address'],ARRAY['mail','email']),
('beneficiary_limits','Payee Limits','Set per-payee transfer limits.','settings','/settings/limits/payees',
  ARRAY['payee limit','beneficiary limit','per recipient limit'],ARRAY['payee limit']),
('savings_goals','Savings Goals','Create and track a savings goal or pocket.','accounts','/accounts/goals',
  ARRAY['goal','savings goal','pocket','envelope','target saving','celengan'],ARRAY['goal','pocket']),
('spending_insights','Spending Insights','See where your money went, broken down by category.','accounts','/insights',
  ARRAY['insights','spending','analytics','budget','where my money','categories'],ARRAY['budget','insight']),
('referral_program','Refer a Friend','Share your referral code and track rewards.','general','/referral',
  ARRAY['referral','refer a friend','invite','promo code','share code'],ARRAY['refer','invite']),
('promotions','Promotions','Browse current offers, discounts and merchant deals.','general','/promotions',
  ARRAY['promo','promotions','offers','deals','discount','vouchers'],ARRAY['promo','deals']),
('terms_privacy','Terms & Privacy','Read the terms of service and privacy policy.','general','/legal',
  ARRAY['terms','privacy','legal','policy','agreement','tnc'],ARRAY['tnc','legal']),
('app_version','About This App','See app version, build number and release notes.','general','/about',
  ARRAY['version','about','build','release notes','app info'],ARRAY['version','about']),
('logout','Log Out','Sign out of the app on this device.','general','/logout',
  ARRAY['logout','sign out','exit','log off','keluar'],ARRAY['logout','signout'])
ON CONFLICT (feature_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. Demo customer data for Stage 3 (entity resolution)
-- ---------------------------------------------------------------------
INSERT INTO accounts (account_id, user_id, label, account_number, account_type, currency, balance_minor) VALUES
('own_001','user_1','Everyday Savings','1234567890','savings','USD', 845233),
('own_002','user_1','Salary Account','1234509876','current','USD', 1290011),
('own_003','user_1','Travel Wallet (EUR)','9988776655','multicurrency','EUR', 220000)
ON CONFLICT (account_id) DO NOTHING;

INSERT INTO payees (payee_id, user_id, nickname, full_name, account_number, bank_code, bank_name, currency) VALUES
('acc_123','user_1','Mom','Jane Doe','5566778899','LOCAL','Local Bank','USD'),
('acc_124','user_1','Dad','John Doe','5566778800','LOCAL','Local Bank','USD'),
('acc_125','user_1','Sis','Emily Doe','5566771122','LOCAL','Local Bank','USD'),
('acc_126','user_1','Landlord','Peter Nguyen','4433221100','GLOBALB','Global Bank','USD'),
('acc_127','user_1',NULL,'Acme Corporation','7788990011','GLOBALB','Global Bank','USD'),
('acc_128','user_1','Bestie','Maria Fernandez','6677889900','LOCAL','Local Bank','EUR')
ON CONFLICT (payee_id) DO NOTHING;

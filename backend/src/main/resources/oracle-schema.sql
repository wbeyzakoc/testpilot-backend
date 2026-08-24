-- ============================================================================
-- TestPilot AI (ai-auto-testing-backend) - Oracle Şeması
-- runs-history.json (RunStore) yerine geçecek ilişkisel tasarım.
-- Kaynak Java sınıfları: Run.java, RunStep.java, ScenarioSuggestion.java,
--                        TestRequest.java, NightlySuiteScheduler.java
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) RUNS — Run.java'nın karşılığı. Her test koşumunun ana kaydı.
-- ----------------------------------------------------------------------------
CREATE TABLE RUNS (
    ID                   VARCHAR2(36)                  NOT NULL,  -- Java UUID.randomUUID().toString(), aynen korunuyor
    GOAL                 VARCHAR2(1000)                NOT NULL,  -- kullanıcının Türkçe test hedefi (örn. "giriş yap")
    STATUS               VARCHAR2(20)                  NOT NULL,  -- running | passed | failed | error | stopped
    ERROR                CLOB,                                    -- LLM/Appium hata mesajı; gözlemde 2000+ karaktere çıkıyor, VARCHAR2(4000) byte limitini riske atmamak için CLOB
    STARTED_AT           TIMESTAMP(6) WITH TIME ZONE   NOT NULL,  -- Instant.now() -> artık string değil, gerçek TIMESTAMP
    FINISHED_AT          TIMESTAMP(6) WITH TIME ZONE,
    STOP_REQUESTED       NUMBER(1)     DEFAULT 0       NOT NULL,  -- Oracle'da native BOOLEAN yoksa (pre-23c) 0/1 kullanılır
    APP_PACKAGE          VARCHAR2(255),                           -- örn. com.saucelabs.SwagLabsMobileApp
    APP_ACTIVITY         VARCHAR2(255),                           -- örn. com.hepsiburada.ui.startup.SplashActivity
    PLATFORM             VARCHAR2(20),                            -- ios | android
    NIGHTLY_SUITE        NUMBER(1)     DEFAULT 0       NOT NULL,
    CAPTURE_SCREENSHOT   NUMBER(1)     DEFAULT 0       NOT NULL,
    RECORD_VIDEO         NUMBER(1)     DEFAULT 0       NOT NULL,
    HAS_VIDEO            NUMBER(1)     DEFAULT 0       NOT NULL,
    FAILURE_SCREENSHOT   BLOB,                                    -- ÖNERİ: AppiumDriverManager.takeScreenshotBase64()'ten gelen
                                                                   -- base64 string DECODE edilip ham binary (PNG) olarak burada
                                                                   -- tutulmalı. Base64 metni aynen saklamak istersen BLOB yerine
                                                                   -- CLOB kullan, ama BLOB hem ~%33 yer kazandırır hem doğru tip olur.
    CONSTRAINT PK_RUNS PRIMARY KEY (ID),
    CONSTRAINT CK_RUNS_STATUS   CHECK (STATUS IN ('running','passed','failed','error','stopped')),
    CONSTRAINT CK_RUNS_PLATFORM CHECK (PLATFORM IN ('ios','android'))
);

COMMENT ON TABLE RUNS IS 'Her test kosumunun (Run.java) tum durumu';
COMMENT ON COLUMN RUNS.FAILURE_SCREENSHOT IS 'Appium screenshot; hata aninda captureScreenshot=true ise doldurulur';

-- ----------------------------------------------------------------------------
-- 2) RUN_STEPS — RunStep.java'nın karşılığı. 1 Run -> N Step.
-- ----------------------------------------------------------------------------
CREATE TABLE RUN_STEPS (
    STEP_ID       NUMBER          GENERATED ALWAYS AS IDENTITY,
    RUN_ID        VARCHAR2(36)    NOT NULL,
    STEP_NO       NUMBER(3)       NOT NULL,             -- RunStep.step (1..MAX_STEPS=15)
    ACTION        VARCHAR2(20)    NOT NULL,             -- tap | type | swipe | wait | done | fail | failed
    TARGET        VARCHAR2(500),                        -- hedeflenen elementin insan-okunur açıklaması
    REASONING     CLOB,                                 -- LLM'in bu adımı neden seçtiğinin gerekçesi (SYSTEM_PROMPT'taki "reasoning")
    CONSTRAINT PK_RUN_STEPS PRIMARY KEY (STEP_ID),
    CONSTRAINT UQ_RUN_STEPS UNIQUE (RUN_ID, STEP_NO),
    CONSTRAINT FK_RUN_STEPS_RUN FOREIGN KEY (RUN_ID) REFERENCES RUNS(ID) ON DELETE CASCADE
);

COMMENT ON TABLE RUN_STEPS IS 'Bir Run icindeki her adim (RunStep.java) - LLM in aksiyon kararlari dahil';

-- ----------------------------------------------------------------------------
-- 3) RUN_SUGGESTIONS — ScenarioSuggestion.java'nın karşılığı. 1 Run -> N Suggestion.
--    (LlmAgent.suggestScenarios / suggestScenariosForPage çıktısı)
-- ----------------------------------------------------------------------------
CREATE TABLE RUN_SUGGESTIONS (
    SUGGESTION_ID  NUMBER          GENERATED ALWAYS AS IDENTITY,
    RUN_ID         VARCHAR2(36)    NOT NULL,
    SENARYO        VARCHAR2(2000)  NOT NULL,            -- kısa Türkçe test cümlesi
    KATEGORI       VARCHAR2(50),                        -- bkz. CK_RUN_SUGG_KATEGORI
    SAYFA          VARCHAR2(255),                       -- senaryonun ilgili olduğu ekran/sayfa
    NEDEN          VARCHAR2(2000),                      -- neden test edilmeye değer olduğunun gerekçesi
    CONSTRAINT PK_RUN_SUGGESTIONS PRIMARY KEY (SUGGESTION_ID),
    CONSTRAINT FK_RUN_SUGG_RUN FOREIGN KEY (RUN_ID) REFERENCES RUNS(ID) ON DELETE CASCADE,
    CONSTRAINT CK_RUN_SUGG_KATEGORI CHECK (KATEGORI IN (
        'Negatif Test','Sınır Durumu','Gezinme Çeşitliliği','UX/Durum Kontrolü','Performans','Erişilebilirlik'
    ))
);

COMMENT ON TABLE RUN_SUGGESTIONS IS 'AI tarafindan onerilen ek test senaryolari (ScenarioSuggestion.java)';

-- ----------------------------------------------------------------------------
-- 4) RUN_VARIABLES — Run.variables / TestRequest.variables (Map<String,String>)
--    normalize edilmiş hali. 1 Run -> N Variable.
-- ----------------------------------------------------------------------------
CREATE TABLE RUN_VARIABLES (
    RUN_ID     VARCHAR2(36)   NOT NULL,
    VAR_KEY    VARCHAR2(100)  NOT NULL,                 -- örn. "mail", "sifre"
    VAR_VALUE  VARCHAR2(1000),                          -- örn. "standard_user"
    CONSTRAINT PK_RUN_VARIABLES PRIMARY KEY (RUN_ID, VAR_KEY),
    CONSTRAINT FK_RUN_VARIABLES_RUN FOREIGN KEY (RUN_ID) REFERENCES RUNS(ID) ON DELETE CASCADE
);

COMMENT ON TABLE RUN_VARIABLES IS 'Run.variables Map alaninin normalize edilmis hali (test degiskenleri)';

-- ----------------------------------------------------------------------------
-- 5) NIGHTLY_SETTINGS — NightlySuiteScheduler'daki hour/minute (şu an in-memory
--    AtomicInteger). Kalıcı olmasını istersen tek satırlık config tablosu.
-- ----------------------------------------------------------------------------
CREATE TABLE NIGHTLY_SETTINGS (
    ID       NUMBER(1)  DEFAULT 1 NOT NULL,             -- her zaman 1, tek satır
    HOUR     NUMBER(2)  DEFAULT 2 NOT NULL,
    MINUTE   NUMBER(2)  DEFAULT 0 NOT NULL,
    CONSTRAINT PK_NIGHTLY_SETTINGS PRIMARY KEY (ID),
    CONSTRAINT CK_NIGHTLY_SETTINGS_ID CHECK (ID = 1)
);

-- ----------------------------------------------------------------------------
-- İndeksler — RunController.listRuns() startedAt'e göre sıralıyor,
-- RunController/NightlySuiteScheduler status ve nightlySuite'e göre filtreliyor.
-- ----------------------------------------------------------------------------
CREATE INDEX IX_RUNS_STARTED_AT ON RUNS(STARTED_AT);
CREATE INDEX IX_RUNS_STATUS     ON RUNS(STATUS);
CREATE INDEX IX_RUNS_NIGHTLY    ON RUNS(NIGHTLY_SUITE);
CREATE INDEX IX_RUN_STEPS_RUN   ON RUN_STEPS(RUN_ID);
CREATE INDEX IX_RUN_SUGG_RUN    ON RUN_SUGGESTIONS(RUN_ID);

-- ============================================================================
-- NOTLAR
-- ============================================================================
-- 1) pom.xml'de şu an Spring Data JPA ve Oracle JDBC driver YOK. Eklemen gerekenler:
--      - com.oracle.database.jdbc:ojdbc11
--      - org.springframework.boot:spring-boot-starter-data-jpa
--    application.properties'e de spring.datasource.* ve
--    spring.jpa.database-platform=org.hibernate.dialect.OracleDialect eklenmeli.
--
-- 2) Oracle sürümün 23c/23ai ise NUMBER(1) yerine native BOOLEAN kolonu da
--    kullanılabilir; 19c/21c gibi daha yaygın sürümlerde NUMBER(1) standarttır.
--
-- 3) STARTED_AT/FINISHED_AT artık Instant.now().toString() ile String değil,
--    gerçek TIMESTAMP olarak tutuluyor — Java tarafında Run.java'daki
--    startedAt/finishedAt alanlarını String yerine Instant/OffsetDateTime'a
--    çevirmen gerekecek (JPA @Entity'ye geçince zaten doğal bir adım).
--
-- 4) RUN_ID FK'ları ON DELETE CASCADE: RunController.deleteRun() bir run'ı
--    sildiğinde steps/suggestions/variables de otomatik silinsin diye.
-- ============================================================================

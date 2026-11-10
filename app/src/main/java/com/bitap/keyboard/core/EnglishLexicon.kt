package com.bitap.keyboard.core

/**
 * Curated offline canonical-spelling lexicon for continuous mixed input (D07).
 *
 * The map is intentionally small and hand-maintained: common English words
 * plus the tech/proper names and acronyms that Chinese IME users type most
 * often inside a pinyin composition (Android, Kotlin, Python, GitHub, Redis,
 * Docker, JSON, SQL, AWS, API, iPhone, Windows, Linux, ...). Lookup is
 * case-insensitive and returns the canonical display spelling, so the resolver
 * can render "android" as "Android" and "json" as "JSON".
 *
 * There is no network access, no mutable state and no platform dependency.
 * Tokens longer than MAX_TOKEN_LENGTH are rejected before hitting the map so
 * suffix splitting in MixedInputResolver stays bounded.
 */
object EnglishLexicon {

    /** Maximum token length accepted by canonicalWord, in UTF-16 chars. */
    const val MAX_TOKEN_LENGTH = 32

    /**
     * Common vocabulary for mixed input. Hand-curated, not third-party
     * frequency data. Single-letter words are deliberately absent: they add
     * little mixed-input value and create noisy one-character suffix splits.
     */
    private val COMMON_WORDS: List<String> = """
        the be to of and in that have it for not on with he as you do at this but
        his by from they we say her she or an will my one all would there their
        what so up out if about who get which go me when make can like time no
        just him know take people into year your good some could them see other
        than then now look only come its over think also back after use two how
        our work first well way even new want because any these give day most us
        is was are been has had were said did more very still here hello hi hey
        help please thanks thank sorry yes maybe welcome morning night today
        tomorrow great nice happy birthday love friend family home phone email
        message name address number date meeting project team office school class
        student teacher computer keyboard input method english chinese goodbye bye
        later soon again always never sometimes need must should try keep let put
        turn open close start stop finish wait send receive read write call check
        confirm update install download upload file folder photo video music game
        news app website link code test bug fix release version build android
        google microsoft apple mobile device battery wifi network market price
        order payment account password

        ok okay yeah sure wonderful awesome beautiful important possible
        impossible easy hard difficult fast slow early late young old big small
        long short high low hot cold warm cool busy free full empty clean dirty
        sad angry tired hungry thirsty ready excited afraid safe dangerous healthy
        sick strong weak rich poor cheap expensive right wrong true false same
        different special normal simple complex modern classic popular famous
        local global public private personal official real main final last next
        previous daily weekly monthly yearly able bad best better black blue brown
        gray green orange pink purple red white yellow delicious fresh funny
        lucky perfect quick quiet serious tall thin useful whole

        water fire earth wind rain snow sun moon star sky cloud tree flower grass
        animal dog cat bird fish horse car bus train plane bike road street city
        country world village town park garden bank shop store restaurant hotel
        hospital university library museum theater cinema station airport factory
        farm kitchen bathroom bedroom table chair bed door window wall floor roof
        room house boy girl man woman child baby father mother son daughter
        brother sister grandfather grandmother uncle aunt cousin husband wife boss
        worker doctor nurse driver police soldier artist writer singer actor player
        user customer manager engineer designer developer programmer

        accept add agree allow answer appear arrive ask become begin believe
        borrow bring buy carry change choose clean climb cook copy count create
        cry cut dance decide delete die draw drive drop eat enjoy enter explain
        fall feel fill find fly follow forget grow happen hear hold hope include
        join jump kill laugh learn leave lend lie listen live lose mean meet move
        offer pay play prefer prepare promise pull push reach remember repeat
        reply return ride run save seem sell shout show sing sit sleep smile speak
        spend stand stay swim talk teach tell throw travel understand visit wake
        walk wash watch wear win wish worry
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * Canonical tech/proper spellings. The lowercase key is derived
     * automatically, so GITHUB, github and GitHub all resolve to GitHub.
     */
    private val TECH_WORDS: List<String> = """
        AI Android Kotlin Java Python JavaScript TypeScript GitHub GitLab Bitbucket
        Redis Docker Kubernetes JSON SQL AWS API iPhone iPad iOS macOS Windows
        Linux Ubuntu Debian Fedora Chrome Firefox Safari Edge Google Microsoft
        Amazon Meta Facebook Instagram Twitter YouTube Netflix Spotify OpenAI
        Anthropic ChatGPT GPT HTTP HTTPS HTML CSS XML YAML REST GraphQL gRPC JDBC
        JVM JRE JDK SDK IDE CLI GUI CPU GPU RAM ROM SSD USB HDMI WiFi Bluetooth
        TensorFlow PyTorch NumPy Sonar React Vue Angular Django Flask Swift Rust Scala
        Ruby Perl PHP MySQL PostgreSQL SQLite MongoDB MariaDB Oracle Cassandra
        Kafka Spark Hadoop Azure GCP Firebase Cloudflare Vercel Netlify Heroku
        Nvidia NVIDIA Intel AMD Qualcomm Samsung Huawei Xiaomi Lenovo Dell Tesla
        Bitcoin Ethereum English Chinese Japanese Korean French German Spanish
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private val CANONICAL_WORDS: Map<String, String> = buildMap {
        // Everyday English words keep their ordinary lowercase spelling.
        COMMON_WORDS.forEach { word -> put(word, word) }
        // Tech/proper names and acronyms override the lowercase form.
        TECH_WORDS.forEach { word -> put(word.lowercase(), word) }
    }

    /**
     * Returns the canonical spelling of raw, or null when the token is blank,
     * longer than MAX_TOKEN_LENGTH, contains non-letter characters or is not
     * in the lexicon. Matching is case-insensitive.
     */
    fun canonicalWord(raw: String): String? {
        if (raw.isEmpty() || raw.length > MAX_TOKEN_LENGTH) return null
        if (raw.any { it !in 'a'..'z' && it !in 'A'..'Z' }) return null
        return CANONICAL_WORDS[raw.lowercase()]
    }

    /** Alias for canonicalWord, kept for callers that prefer lookup wording. */
    fun lookup(raw: String): String? = canonicalWord(raw)

    /** True when raw is a known English token (case-insensitive). */
    fun isKnown(raw: String): Boolean = canonicalWord(raw) != null

    /**
     * True when raw is a known acronym or proper noun whose canonical spelling
     * is not all-lowercase (for example API, GitHub, iPhone).
     *
     * MixedInputResolver uses this to allow very short canonical suffixes
     * such as API while still rejecting short lowercase function words
     * (a, an, in, to) that would mis-segment ordinary pinyin.
     */
    fun isProperName(raw: String): Boolean {
        val canonical = canonicalWord(raw) ?: return false
        return canonical != canonical.lowercase()
    }

    /** Alias for isProperName, expressed in terms of the stored spelling. */
    fun hasCanonicalCasing(raw: String): Boolean = isProperName(raw)

    /** Number of curated entries; useful for diagnostics/tests. */
    val size: Int
        get() = CANONICAL_WORDS.size
}

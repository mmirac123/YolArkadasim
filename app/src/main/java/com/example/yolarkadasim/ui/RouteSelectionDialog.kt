package com.example.yolarkadasim.ui

import android.app.Dialog
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.yolarkadasim.R
import com.example.yolarkadasim.data.BusRoute
import com.example.yolarkadasim.data.BusStop
import com.example.yolarkadasim.data.FavoritesStore
import com.example.yolarkadasim.data.RecentDestination
import com.example.yolarkadasim.data.RecentDestinationStore
import com.example.yolarkadasim.util.StopMatcher
import com.google.android.material.button.MaterialButton
import java.util.Locale

class RouteSelectionDialog : DialogFragment(), TextToSpeech.OnInitListener {

    var onRouteAndStopSelected: ((BusRoute, BusStop, Int) -> Unit)? = null

    private var routes: List<BusRoute> = emptyList()
    private var selectedRoute: BusRoute? = null

    private lateinit var textTitle: TextView
    private lateinit var btnBack: MaterialButton
    private lateinit var editSearchStop: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RouteSelectionAdapter
    private lateinit var favoritesStore: FavoritesStore
    private var allStopOptions: List<SelectionOption> = emptyList()
    private var allRouteOptions: List<SelectionOption> = emptyList()

    // Arama dizinleri: normalize() her tuş vuruşunda binlerce kez çağrılmasın
    // diye seçenek metinleri bir kez normalize edilip saklanır
    private var routeSearchIndex: List<Pair<SelectionOption, String>> = emptyList()
    private var stopSearchIndex: List<Pair<SelectionOption, String>> = emptyList()
    private var stopNamesByRoute: Map<String, String> = emptyMap()

    // Recent Destination UI
    private lateinit var layoutRecentDest: LinearLayout
    private lateinit var cardRecentDest: LinearLayout
    private lateinit var textRecentDestName: TextView
    private lateinit var textRecentDestRoute: TextView
    private var recentDestination: RecentDestination? = null
    
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isEasyMode = false

    companion object {
        fun newInstance(routes: List<BusRoute>, isEasyMode: Boolean = false): RouteSelectionDialog {
            return RouteSelectionDialog().also {
                it.routes = routes
                it.isEasyMode = isEasyMode
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_YolArkadasim)
        tts = TextToSpeech(requireContext(), this)
        favoritesStore = FavoritesStore(requireContext())
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.forLanguageTag("tr-TR"))
            // Ana ekranla aynı konuşma hızı ayarına uy
            try {
                context?.let { tts?.setSpeechRate(com.example.yolarkadasim.data.SettingsStore(it).getSpeechRate() / 100f) }
            } catch (e: Exception) { /* ayar okunamazsa varsayılan hız */ }
            isTtsReady = true
            
            // Auto guidance on open
            if (isEasyMode) {
                if (selectedRoute == null) {
                    speak("Hat seçme ekranı açıldı. Listeden istediğiniz otobüs hattını seçin. İsimleri duymak için sağdaki küçük hoparlörlere basabilirsiniz.")
                }
            }
        }
    }

    private fun speak(message: String) {
        if (isTtsReady) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "dialog_preview")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_select_destination, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textTitle = view.findViewById(R.id.textDialogTitle)
        btnBack = view.findViewById(R.id.btnDialogBack)
        editSearchStop = view.findViewById(R.id.editSearchStop)
        recyclerView = view.findViewById(R.id.recyclerOptions)

        editSearchStop.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // StopMatcher.normalize: Türkçe İ/ı kuralları + aksan sadeleştirme —
                // "umitkoy" yazan da "Ümitköy"ü bulur
                val query = StopMatcher.normalize(s?.toString() ?: "")
                if (selectedRoute != null) {
                    val filtered = if (query.isEmpty()) allStopOptions
                    else stopSearchIndex.filter { it.second.contains(query) }.map { it.first }
                    adapter.updateItems(filtered)
                } else {
                    adapter.updateItems(filterRoutes(query))
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        layoutRecentDest = view.findViewById(R.id.layoutRecentDest)
        cardRecentDest = view.findViewById(R.id.cardRecentDest)
        textRecentDestName = view.findViewById(R.id.textRecentDestName)
        textRecentDestRoute = view.findViewById(R.id.textRecentDestRoute)

        adapter = RouteSelectionAdapter(
            onItemClick = { position -> onItemSelected(position) },
            onAudioClick = { position -> onAudioPreviewClicked(position) },
            onFavoriteClick = { position -> onFavoriteToggleClicked(position) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        btnBack.setOnClickListener {
            if (selectedRoute == null) {
                dismiss()
            } else {
                selectedRoute = null
                showRouteList()
            }
        }

        loadRecentDestination()
        showRouteList()
    }

    private fun loadRecentDestination() {
        val store = RecentDestinationStore(requireContext())
        val recent = store.load() ?: return
        val matchingRoute = routes.find { it.routeId == recent.routeId } ?: return
        val validIndex = recent.stopIndex in matchingRoute.stops.indices
        val matchingStop = if (validIndex) matchingRoute.stops[recent.stopIndex] else null
        if (matchingStop == null || matchingStop.id != recent.stopId) return

        recentDestination = recent
        textRecentDestName.text = recent.stopName
        textRecentDestRoute.text = "Hat ${recent.routeId}"

        cardRecentDest.setOnClickListener {
            speak(getString(R.string.tts_recent_dest_selected, recent.stopName))
            onRouteAndStopSelected?.invoke(matchingRoute, matchingStop, recent.stopIndex)
            dismiss()
        }
    }

    /**
     * Hat listesi filtresi: önce hat numarası/adı/güzergâh eşleşmeleri,
     * ardından "bu duraktan hangi hatlar geçiyor" eşleşmeleri gelir.
     */
    private fun filterRoutes(query: String): List<SelectionOption> {
        if (query.isEmpty()) return allRouteOptions
        val direct = routeSearchIndex.filter { it.second.contains(query) }.map { it.first }
        // Durak adından hat bulma (kısa sorgularda gürültü olmasın diye >= 3 harf)
        val viaStopIds = if (query.length >= 3) {
            if (stopNamesByRoute.isEmpty()) {
                // İlk uzun sorguda bir kez kurulur (119 hat × ~50 durak)
                stopNamesByRoute = routes.associate { r ->
                    r.routeId to r.stops.joinToString(" ") { StopMatcher.normalize(it.name) }
                }
            }
            val directIds = direct.map { it.id }.toSet()
            stopNamesByRoute.asSequence()
                .filter { (id, names) -> id !in directIds && names.contains(query) }
                .map { it.key }
                .toSet()
        } else emptySet()
        return direct + allRouteOptions.filter { it.id in viaStopIds }
    }

    private fun showRouteList() {
        textTitle.text = getString(R.string.select_route_title)
        btnBack.visibility = View.VISIBLE
        btnBack.text = "ANA EKRAN"
        editSearchStop.visibility = View.VISIBLE
        editSearchStop.hint = getString(R.string.search_route_hint)
        editSearchStop.text.clear()
        layoutRecentDest.visibility = if (recentDestination != null) View.VISIBLE else View.GONE

        val favRoutes = favoritesStore.getFavoriteRoutes()

        val options = routes.map { route ->
            SelectionOption(
                id = route.routeId,
                badge = route.routeId,
                title = route.routeName,
                subtitle = "${route.originName} → ${route.terminusName}",
                showAudioIcon = true,
                isFavorite = favRoutes.contains(route.routeId)
            )
        }.sortedByDescending { it.isFavorite }

        allRouteOptions = options
        routeSearchIndex = options.map {
            it to StopMatcher.normalize("${it.badge} ${it.title} ${it.subtitle}")
        }
        adapter.updateItems(options)
        recyclerView.scrollToPosition(0)
    }

    private fun showStopList(route: BusRoute) {
        textTitle.text = getString(R.string.select_stop_title)
        btnBack.visibility = View.VISIBLE
        btnBack.text = "← GERİ"
        editSearchStop.visibility = View.VISIBLE
        editSearchStop.hint = getString(R.string.search_stop_hint)
        editSearchStop.text.clear()
        layoutRecentDest.visibility = View.GONE

        // Guidance for stop list
        if (isEasyMode) {
            speak("${route.routeId} numaralı hattın durakları listelendi. Lütfen ineceğiniz durağı seçin.")
        }

        val favStops = favoritesStore.getFavoriteStopsForRoute(route.routeId)
        
        val options = route.stops.map { stop ->
            val fav = favStops.find { it.stopId == stop.id }
            SelectionOption(
                id = stop.id,
                badge = stop.id,
                title = fav?.customName ?: stop.name,
                subtitle = if (fav?.customName != null) stop.name else "",
                showAudioIcon = true,
                isFavorite = fav != null
            )
        }.sortedByDescending { it.isFavorite }

        allStopOptions = options
        stopSearchIndex = options.map {
            // EGO durak numarası (id) da aranabilir
            it to StopMatcher.normalize("${it.id} ${it.title} ${it.subtitle}")
        }
        adapter.updateItems(options)
        recyclerView.scrollToPosition(0)
    }

    private fun onFavoriteToggleClicked(position: Int) {
        val item = adapter.getItem(position)
        if (selectedRoute == null) {
            favoritesStore.toggleRouteFavorite(item.id)
            showRouteList()
        } else {
            if (item.isFavorite) {
                favoritesStore.removeFavoriteStop(selectedRoute!!.routeId, item.id)
                showStopList(selectedRoute!!)
            } else {
                showNicknameDialog(selectedRoute!!.routeId, item.id)
            }
        }
    }

    private fun showNicknameDialog(routeId: String, stopId: String) {
        val input = EditText(requireContext())
        input.hint = "Örn: Evim, Okul, İş"
        
        AlertDialog.Builder(requireContext())
            .setTitle("Favori Durağa İsim Ver")
            .setMessage("Bu durağı nasıl kaydetmek istersiniz?")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val nickname = input.text.toString().trim().ifEmpty { null }
                favoritesStore.saveFavoriteStop(routeId, stopId, nickname)
                showStopList(selectedRoute!!)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun onAudioPreviewClicked(position: Int) {
        val item = adapter.getItem(position)
        if (selectedRoute == null) {
            speak("Hat ${item.badge}. ${item.title}.")
        } else {
            speak(item.title)
        }
    }

    private fun onItemSelected(position: Int) {
        val item = adapter.getItem(position)
        if (selectedRoute == null) {
            selectedRoute = routes.find { it.routeId == item.id }
            showStopList(selectedRoute!!)
        } else {
            val route = selectedRoute!!
            val stop = route.stops.find { it.id == item.id }!!
            val stopIndex = route.stops.indexOf(stop)
            onRouteAndStopSelected?.invoke(route, stop, stopIndex)
            dismiss()
        }
    }
}

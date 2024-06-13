package com.example.driverapp.ui.home

import android.Manifest
import android.content.res.Resources
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.driverapp.Common
import com.example.driverapp.R
import com.example.driverapp.databinding.FragmentHomeBinding
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import java.io.IOException
import java.util.Locale

class HomeFragment : Fragment(), OnMapReadyCallback {
    private var _binding: FragmentHomeBinding? = null
    private lateinit var mMap: GoogleMap
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var mapFragment: SupportMapFragment

    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private lateinit var driversLocationRef: DatabaseReference
    private var currentUserRef: DatabaseReference? = null
    private lateinit var onlineRef: DatabaseReference
    private lateinit var geoFire: GeoFire


    private val onlineValueEventListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            if (snapshot.exists() && currentUserRef != null)
                currentUserRef!!.onDisconnect().removeValue()
        }

        override fun onCancelled(p0: DatabaseError) {
            Snackbar.make(mapFragment.requireView(), p0.message, Snackbar.LENGTH_LONG).show()
        }
    }

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onDestroy() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        geoFire.removeLocation(FirebaseAuth.getInstance().currentUser!!.uid)
        onlineRef.removeEventListener(onlineValueEventListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        registerOnlineSystem()
    }

    private fun registerOnlineSystem() {
        onlineRef.addValueEventListener(onlineValueEventListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        init()

        mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        return root
    }

    private fun init() {
        onlineRef = FirebaseDatabase.getInstance().getReference().child(".info/connected")

        locationRequest = LocationRequest()
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
        locationRequest.setFastestInterval(15000)
        locationRequest.interval = 10000
        locationRequest.setSmallestDisplacement(50f)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)

                val newPos = LatLng(
                    locationResult.lastLocation!!.latitude,
                    locationResult.lastLocation!!.longitude
                )
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newPos, 18f))

                val geoCoder = Geocoder(requireContext(), Locale.getDefault())
                val addressList: List<Address>?
                try {
                    addressList = geoCoder.getFromLocation(
                        locationResult.lastLocation!!.latitude,
                        locationResult.lastLocation!!.longitude, 1
                    )
                    val cityName = addressList!![0].locality

                    driversLocationRef = FirebaseDatabase.getInstance()
                        .getReference(Common.DRIVERS_LOCATION_REFERENCE).child(cityName)
                    currentUserRef =
                        driversLocationRef.child(FirebaseAuth.getInstance().currentUser!!.uid)
                    geoFire = GeoFire(driversLocationRef)

                    geoFire.setLocation(
                        FirebaseAuth.getInstance().currentUser!!.uid,
                        GeoLocation(
                            locationResult.lastLocation!!.latitude,
                            locationResult.lastLocation!!.longitude
                        )
                    ) { key: String?, error: DatabaseError? ->
                        if (error != null) {
                            Snackbar.make(
                                mapFragment.requireView(),
                                error.message,
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                    registerOnlineSystem()

                } catch (e: IOException) {
                    Snackbar.make(requireView(), e.message!!, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
        fusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(requireContext())
        fusedLocationProviderClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.myLooper()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        Dexter.withContext(context)
            .withPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            .withListener(object : PermissionListener {
                override fun onPermissionGranted(p0: PermissionGrantedResponse?) {
                    mMap.isMyLocationEnabled = true
                    mMap.uiSettings.isMyLocationButtonEnabled = true
                    mMap.setOnMyLocationClickListener {
                        Toast.makeText(context, "Button Clicked", Toast.LENGTH_SHORT).show()
                        fusedLocationProviderClient.lastLocation
                            .addOnFailureListener { e ->
                                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                            }
                            .addOnSuccessListener { location ->
                                val userLatLng = LatLng(location.latitude, location.longitude)
                                mMap.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        userLatLng,
                                        18f
                                    )
                                )
                            }
                        true
                    }

                    val view =
                        mapFragment.requireView().findViewById<View>("1".toInt())?.parent as View
                    val locationButton = view.findViewById<View>("2".toInt())
                    val params = locationButton.layoutParams as RelativeLayout.LayoutParams
                    params.addRule(RelativeLayout.ALIGN_TOP, 0)
                    params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
                    params.bottomMargin = 50
                }

                override fun onPermissionDenied(p0: PermissionDeniedResponse?) {
                    Toast.makeText(
                        context,
                        "Permission" + p0?.permissionName + " was denied",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onPermissionRationaleShouldBeShown(
                    p0: PermissionRequest?,
                    p1: PermissionToken?
                ) {
                    TODO("Not yet implemented")
                }

            }).check()

        try {
            val success = googleMap.setMapStyle(context?.let {
                MapStyleOptions.loadRawResourceStyle(
                    it, R.raw.uber_maps_style
                )
            })
            if (!success)
                Log.e("Error", "Style parsing error")
        } catch (e: Resources.NotFoundException) {
            e.message?.let { Log.e("Error", it) }
        }
        Snackbar.make(mapFragment.requireView(), "You're online!", Snackbar.LENGTH_SHORT).show()
    }

}





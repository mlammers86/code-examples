package pps.storm.servlet;

import java.io.*;
import java.util.*;
import java.math.*;
import java.time.*;
import java.time.format.*;
import java.text.SimpleDateFormat;
import java.util.zip.GZIPOutputStream;

import java.nio.file.*;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.math3.special.Gamma;
import ch.systemsx.cisd.hdf5.*;
import ch.systemsx.cisd.base.mdarray.*;

import pps.im.db.data.DbPersonInfo;
import pps.im.exception.ImException;
import pps.util.mail.PpsEmailNotify;
import pps.storm.config.StormProperty;
import pps.util.PpsException;

/**
 * API Servlet for requests for OpenSSP data.
 * <p>
 * Created: April 18, 2016
 * Revised: $Author$ revised on $Date$
 * <p>
 * @author Matt Lammers
 * @version $Revision$
 */

public class OpenSSPAPI extends HttpServlet {
	private static String serverDir = "****"; //Edited out for security
	private static String version = "18.09.003"; //Update when there are output changes in API (YY.MM.index)

	public void doPost (HttpServletRequest request, HttpServletResponse response)
		throws ServletException, IOException
	{
        String[] fname = request.getParameterValues("filename");
        String[] emails = request.getParameterValues("email");
        
		System.out.println("Generating OpenSSP Response");

        PrintWriter out = response.getWriter();
        
        //If the user requested the data online/programmatically
        String outStr = grabTheData(request,response);
        out.println(outStr);
        return;
	}

	void sendEmail(String subject, String body, String to, String fpath)
    {
    	//Helper function to send out an email
        try
        {
            System.out.println("Email sent to: " + to + " at " + new Date() + " about " + subject);
            getEmailHandler().send(StormProperty.EMAIL_SENDER.getValue(), to, subject, body, null, fpath);
        }
        catch (PpsException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

	PpsEmailNotify getEmailHandler()
    {
        if (this.emailHandler == null)
        {
            this.emailHandler = new PpsEmailNotify();
        }
        return emailHandler;
    }

	private PpsEmailNotify emailHandler;

	private Boolean newEmail(String em) {
		/*
		This checks to see if a user has used the OpenSSP site before or not. We use
		this to add users to the OpenSSP mailing list.
		*/
		try {
			File emFile = new File(serverDir+"OpenSSP/ossp_email.txt");
			String allEmails = new Scanner(emFile).useDelimiter("\\Z").next();
			return !allEmails.toLowerCase().contains(em.toLowerCase());
		} catch (IOException e) {
			System.out.println("OpenSSP File Read Failed?");
			return false;
		}
    }
    
    private String grabTheData(HttpServletRequest request, HttpServletResponse response) {

		//This first block of variables is non-numerical
		String[] emails = request.getParameterValues("email");
		String[] frequencies = request.getParameterValues("frequency");
		String[] bsArr = request.getParameterValues("bs"); //"bin size Array"
		String[] sizes = request.getParameterValues("size");
		if (sizes != null) {
			if (sizes[0].split(",").length>20 && bsArr == null) {
				return "Invalid request, discrete size limit is 20.";
			}
		}
		String outStr = "";
		if ((frequencies != null) && (sizes != null)) {
			//Use this to determine what orders are made in the log file
			System.out.println("Parameters are: "+emails[0]+","+frequencies[0]+","+sizes[0]+"|"+new Date());
		}
		String[] delimiter = request.getParameterValues("delimiter");
		//Tab versus comma-separated
		String separate = "\t";
		if (delimiter != null) {
			if (delimiter[0].equals("comma")) {
				separate = ",";
			}
		}
		String[] validate = request.getParameterValues("validate");
		String[] textOnly = request.getParameterValues("text");
		//Is it HTML-formatted or text only
		boolean tO = false;
		if (textOnly != null) {
			if (textOnly[0].equals("true")) {
				tO = true;
			}
		}

		//This begins the numerical variables
		double nnstar = -9999;
		String[] dsubmArr = request.getParameterValues("dsubm");
		double dsubm = -9999;
		double bs = -9999;
		String[] bunit = request.getParameterValues("bunit");
		String[] muArr = request.getParameterValues("mu");
		double mu = 0;
		double lam = -9999; //lambda
		if (dsubmArr != null) {
			//Have to adjust Dm based on "bin unit" value
			dsubm = Double.parseDouble(dsubmArr[0]);
			bs = Double.parseDouble(bsArr[0]);
			if (bunit[0].equals("millimeters")) {
				dsubm = dsubm/1e3;
				bs = bs/1e3;
			} else if (bunit[0].equals("meters")) {
				dsubm = dsubm/1e6;
				bs = bs/1e6;
			}
		}
		if (muArr != null) {
			mu = Double.parseDouble(muArr[0]);
		}

		//These are the mass-dimension-only variables...either they're all here or none are.
		String[] mdra = request.getParameterValues("aval");
		double aval = -9999;
		String[] mdrb = request.getParameterValues("bval");
		double bval = -9999;
		String[] mdrd = request.getParameterValues("diff");
		double dval = -9999;
		if (mdra != null) {
			aval = Double.parseDouble(mdra[0]);
			bval = Double.parseDouble(mdrb[0]);
			dval = Double.parseDouble(mdrd[0]);
		}

		String[] sesh = request.getParameterValues("sessionId");
		String sesid = "";
		if (sesh != null) {
			sesid = sesh[0];
		}

		String[] query = request.getParameterValues("qnum");
		String qnum = "";
		if (query != null) {
			qnum = query[0];
        }

        //Particle Size Distribution Request in HTML output format
        System.out.println("Making a graph output");
        response.setContentType("text/html");
        //Create start of the HTML output
        outStr = "<!doctype html><html lang='en'><body style='font: 10px serif;'><button onclick='console.log(\"Clicked the button\"); dld();'>Download As Text File</button></br></br>";
        String[] sSplit = sizes[0].split(",");
        String bUnit = bunit[0];
        String uabb = "um";
        if (bUnit.equals("millimeters")) {
            uabb = "mm";
        } else if (bUnit.equals("meters")) {
            uabb = "m";
        }
        String shp = "";
        String sep = separate;
        String[] fSplit = "003.002GHz,005.003GHz,010.657GHz,013.609GHz,018.713GHz,023.816GHz,035.525GHz,089.062GHz,094.065GHz,150.104GHz,165.615GHz,176.422GHz,180.425GHz,186.429GHz,190.432GHz".split(",");
        if (frequencies != null) {
            if (!frequencies[0].equals("all")) {
                fSplit = frequencies[0].split(",");
            }
        }
        String[] wls = "99931.0,59958.0,25150.0,22044.0,16032.0,12596.0,8444.9,3368.5,3189.3,1998.6,1811.4,1700.5,1662.7,1609.2,1575.4".split(",");
        String varNames = "Frequency[GHz],Wvlngth[um],Size["+uabb+"],D_max[um],rho_D_max[g/cm^3],Q_bk,Q_ext,Q_sca,Q_abs,g,Volume[um^3],Prj_Area[um^2],Sfc_Area[um^2],r_eq_vol[um],r_eq_prj[um],r_eq_sfc[um],L_lx[um],L_ly[um],L_lz[um],rho_elps[g/cm^3],d_fractal,bin_count";
        String[] ssNew = sSplit;
        List<String> sTemp = new ArrayList<String>();

        //The part where I make the shape/size and number lists
        double dMax = 0;
        double dMin = 9999;
        double tval = 0;
        List<Double> ssNum = new ArrayList<Double>();

        //Since we're worrying about mass-dimension relationship, there's a lot more to do.
        int totCount = 0;
        int inDiffCt = 0;
        File shapeDir = new File(serverDir+"OpenSSP/shape/");
        String[] tempShapes = shapeDir.list();
        String tempShp = "";
        //What we're doing here is determining which size particles fall within the mass-dimension relationship...note the call to "inDiff"
        for (int k=0;k<tempShapes.length;k++) {
            tempShp = tempShapes[k];
            File osspDir = new File(serverDir+"OpenSSP/shape/"+tempShp+"/size/");
            String[] tempSplit = osspDir.list();
            String tempComb = "";
            for (int j=0;j<tempSplit.length;j++) {
                tempComb = tempShp+"/"+tempSplit[j];
                totCount++;
                if (inDiff(tempComb,aval,bval,dval)) {
                    inDiffCt++;
                    sTemp.add(tempShp+"/"+tempSplit[j]); //Here we add the sizes to the list of files to eventually go through.
                }
            }
        }
        System.out.println("Total Sizes: "+totCount);
        System.out.println("Sizes in diff: "+inDiffCt);

        //We have to rescale the values depending on the units they come in
        double scale = 1;
        if (bUnit.equals("microns")) {
            scale = 1e6;
        } else if (bUnit.equals("millimeters")) {
            scale = 1e3;
        }
        Collections.sort(sTemp); //It helps to have the sizes in numerical order
        //Here we loop through the sizes to find the largest and smallest ones
        for (int q=0;q<sTemp.size();q++) {
            tval = Double.parseDouble(sTemp.get(q).split("/")[1].replace("um",""))*1.942/1e6;
            ssNum.add(tval*scale);
            if (tval>dMax) {
                dMax = tval;
            } else if (tval<dMin) {
                dMin = tval;
            }
        }
        dMin = Math.floor(dMin/bs)*bs; //It makes more sense to bin rounded to the nearest bin size
        ssNew = sTemp.toArray(ssNew);

        //The part where I bin the sizes based on bin size/max/min
        double dBS = bs;
        double newMax;
        double newMin;
        double newBS;
        newMax = dMax*scale;
        newMin = dMin*scale;
        newBS = dBS*scale;
        String[] bins = new String[(int)((newMax-newMin)/newBS)+1];
        int bininc = 0;
        //This loop creats the "bin strings" to use in the tsv/csv output
        for (double q=newMin;q<newMax;q+=newBS){
            if (bininc<bins.length) {
                if (bUnit.equals("microns")) {
                    bins[bininc] = String.valueOf(Math.round(q))+"-"+String.valueOf(Math.round(q+newBS));
                } else {
                    bins[bininc] = round(q,6)+"-"+round(q+newBS,6);
                }
            }
            bininc++;
        }
        //Here we get the actual numerical values for the bin ranges
        double[] dbins = new double[(int)((newMax-newMin)/newBS)+1];
        int binc = 0;
        for (double q=dMin;q<(dMax+dBS);q+=dBS){
            if (binc<dbins.length) {
                dbins[binc] = q;
                binc++;
            }
        }
        //This places the sizes into their respective size bins
        TreeMap<String,ArrayList<String>> sizeHash = new TreeMap<String,ArrayList<String>>();
        for (int d=0;d<ssNum.size();d++) {
            int whichBin = (int)Math.floor((ssNum.get(d)-newMin)/newBS);
            if (whichBin>=bins.length || whichBin<0) {
                continue;
            }
            if (sizeHash.containsKey(bins[whichBin])) {
                ArrayList<String> thisBin = sizeHash.get(bins[whichBin]);
                thisBin.add(ssNew[d]);
                sizeHash.put(bins[whichBin],thisBin);
            } else {
                ArrayList<String> thisBin = new ArrayList<String>();
                thisBin.add(ssNew[d]);
                sizeHash.put(bins[whichBin],thisBin);
            }
        }
        //Here we continue generating the output text for the API
        String datText = "#OpenSSPAPI Version "+version+"\n"+"#Email: "+emails[0]+"\n";
        if (bunit[0].equals("millimeters")) {
            hunit = "mm";
            dsubm_adj = dsubm*1e3;
            bs_adj = bs*1e3;
        } else if (bunit[0].equals("meters")) {
            hunit = "m";
            dsubm_adj = dsubm*1e6;
            bs_adj = bs*1e6;
        }
        datText = datText+"#Session ID: "+sesid+"; Query Number: "+qnum+"\n#Request String: OpenSSPAPI?"+request.getQueryString().split("&session")[0]+"\n";
        datText = datText+varNames.replace(",",sep);
        double msum = 0;
        lam = getLam(dMax,dsubm,mu);
        nnstar = getNoHat(lam,mu,dMax);
        //This first loop just gets the accumulated mass for all of the particles in their respective size bins.
        //This is necessary for the normalization to 1 g/m^3
        for (int f=0;f<fSplit.length;f++) {
            for (int z=0;z<bins.length;z++) {
                if (sizeHash.containsKey(bins[z])) {
                    double binCount = countSomeBins(dbins[z],nnstar,dsubm,bs,mu,lam);
                    ArrayList<String> sizez = sizeHash.get(bins[z]);
                    String gab = getABin(sep,fSplit[f],sizez.toArray(new String[0]),true);
                    String[] gsplit = gab.split("~");
                    msum = msum+Double.parseDouble(gsplit[1])*binCount;
                }
            }
        }
        System.out.println("Accumulated Mass: "+msum);
        //Now that I know how much total mass there is, I can scale based on that value the mass contribution of the individual bins.
        for (int f=0;f<fSplit.length;f++) {
            for (int z=0;z<bins.length;z++) {
                if (!sizeHash.containsKey(bins[z])) {
                    datText = datText+"\n"+fSplit[f].replace("GHz","")+sep+wls[f]+sep+bins[z]+sep;
                    for (int d=0;d<17;d++) {
                        datText = datText+"-9999"+sep;
                    }
                    datText = datText+"-9999";
                } else {
                    double binCount = countSomeBins(dbins[z],nnstar,dsubm,bs,mu,lam);
                    ArrayList<String> sizez = sizeHash.get(bins[z]);
                    //The datText string has a new line added for every loop
                    datText = datText+"\n"+fSplit[f].replace("GHz","")+sep+wls[f]+sep+bins[z]+sep;
                    datText = datText+getABin(sep,fSplit[f],sizez.toArray(new String[0]),false)+sep+(binCount/msum);
                }
            }
        }

        datText = datText+"\n#Derived Parameters: minimum-levd="+newMin+" "+hunit+"; maximum-levd="+newMax+" "+hunit+"; lambda="+lam+" "+hunit+"-1; n-nought-star="+nnstar+" "+hunit+"-1 m-3\n#PLEASE NOTE: All values are Arithmetic Averages of the values for the sizes in the OpenSSP database.\n#Variable Explanations\n#D_max: maximum dimension/diameter\n#rho_D_max: snow density based on D_max, ice mass of the particle divided by the volume of a sphere with D_max as its diameter\n#Q_bk: backscattering efficiency\n#Q_ext: extinction efficiency\n#Q_sca: scattering efficiency\n#Q_abs: absorption efficiency\n#g: asymmetry factor\n#Prj_Area: orientation-averaged projection area of the particle\n#Sfc_Area: surface area of the particle\n#r_eq_vol: radius of a sphere having the same volume as the ice mass of the particle; used to identify particle size in OpenSSP database\n#r_eq_prj: radius of a sphere having the same projection area as the orientation-averaged projection area of the particle\n#r_eq_sfc: radius of a sphere having the same surface area as that of the particle\n#L_lx: length of the shortest axis of a circumscribing ellipsoid of the particle that is parallel to the axis of maximum moment of inertia\n#L_ly: length of the intermediate axis of a circumscribing ellipsoid perpendicular to both axes of the maximum and minimum moments of inertia\n#L_lz: length of the longest axis of a circumscribing ellipsoid of the particle that is parallel to the axis of minimum moment of inertia\n#rho_elps: snow density based on the circumscribing ellipsoid, ice mass of the particle divided by the volume of the circumscribing ellipsoid\n#bin_count: number of particles in the bin based on the particle size distributions defined by the given parameters";
        String outFormat = "tsv";
        if (separate.equals(",")) {
            outFormat = "csv";
        }
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String dText = now.format(formatter);
        outStr += datText.replaceAll("[\n]","<br/>")+"<br/><br/><button onclick='console.log(\"Clicked the button\"); dld();'>Download As Text File</button><script>"+
            "function dld() {console.log('Trying to Download!'); if (navigator.msSaveOrOpenBlob) {var txt = '"+datText.replaceAll("[\n]",";")+"'; var b = new Blob([txt.replace(/;/g,String.fromCharCode(10))], {type: 'text/plain'}); navigator.msSaveOrOpenBlob(b,'"+dText+"_query."+outFormat+"');} else {var element = document.createElement('a'); var txt = '"+datText.replaceAll("[\n]",";")+
            "'; element.setAttribute('href', 'data:text/plain;charset=utf-8,'+encodeURIComponent(txt.replace(/;/g,String.fromCharCode(10)))); element.setAttribute('download', '"+dText+"_query."+outFormat+"');"+
            " element.style.display = 'none'; document.body.appendChild(element); element.click(); document.body.removeChild(element);}}"+
            "</script></body></html>";
        return outStr;
    }

    private boolean inDiff(String sze, double a, double b, double d) {
		/*
        Testing whether a particle falls within some percent difference of mass from an ideal mass-dimension relation
        Inputs: 
        sze - shape/size identifier string
        a - the a parameter in the exponential equation defining mass-dimension relationship
        b - the b parameter in the exponential equation defining mass-dimension relationship
        d - the percent difference allowed between the real mass and the mass-dimension defined mass
		*/
		String shape = sze.split("/")[0];
		String size = sze.split("/")[1];
		IHDF5Reader preader = HDF5Factory.openForReading(serverDir+"OpenSSP/shape/"+shape+"/size/"+size+"/pblock.h5");
		IHDF5FloatReader pfleader = preader.float32();
		double levd = (double)pfleader.read("/EVOL_RADIUS")*2;
		double md = (double)pfleader.read("/MAX_DIM")/10000.0;
		double mass = Math.pow(levd/1.0e6,3)*Math.PI/6*1.0e6;
		double modmass = a*Math.pow(md,b);
		preader.close();
		if (Math.abs(mass-modmass)/modmass*100<d) {
			return true;
		} else {
			return false;
		}
	}

	private double incGamma(double a, double x) {
		//RegularizedGammaP is normalized, so I have to multiply by gamma to get actual incomplete value.
		return Gamma.gamma(a)*Gamma.regularizedGammaP(a,x);
	}

	private double getLam(double dmax, double dm, double mu) {
        /*
        Based on Kwo-Sen Kuo's equations and a bisection algorithm for determining the root of a function (lamBase), we find the closest Lambda
        Inputs:
        dmax - maximum radius of particles in the bin
        dm - mean radius of particles in the bin
        mu - user-specified value for the gamma function for the parameter mu
        */
		double tol = 1e-6;
		double a = 1;
		double b = 10000;
		double c = 0;
		int j = 0;
		while ((b-a)/2>tol) {
			c = (a+b)/2;
			double fc = lamBase(dm,dmax,mu,c);
			double fa = lamBase(dm,dmax,mu,a);
			if (fc == 0) {
				break;
			}
			if ((fc>0 && fa>0) || (fc<0 && fa<0)) {
				a = c;
			} else {
				b = c;
			}
			j++;
			if (j>10000) {
				break;
			}
		}
		return c;
	}

	private double lamBase(double dm,double dmax,double mu,double tlam) {
		//The function for minimizing to determine lambda
		return dm - incGamma(5+mu,tlam*dmax)/incGamma(4+mu,tlam*dmax)/tlam;
	}

	private double getNoHat(double lamda,double mu,double dmax) {
		//Based on lambda, this gets the value of N0*
		double nohat = 0;
		nohat = 6/Math.PI/1.0e6*Math.pow(lamda,4+mu)/incGamma(4+mu,lamda*dmax);
		return nohat;
	}

	private double countSomeBins(double z, double n, double d, double bs, double mu, double l) {
        /*
        Solves for the particle size distribution - same whether gamma or exponential (just mu = 0)
        This is essentially an implementation of the trapezoidal rule for a given bin to
        approximate the value of an integral.
        */
		double evals = 0;
		double gx = 0;
		double fmu = 0;
		for (double it = 0;it<6;it++) {
			gx = (z+it*bs/5.);
			fmu = Math.pow(gx,mu)*Math.exp(-l*gx);
			if (it<1 || it>4) {
				evals = evals+fmu/2;
			} else {
				evals = evals+fmu;
			}
		}
		evals = n*bs/5*evals;
		return evals;
	}

	private void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        /*
        Helper function to ensure if someone does a GET rather than PUT request, it does the same thing
        */
        doPost(request, response);

        return;
    }

    private boolean validateUser(String uid) {
		//Checking to make sure email address is valid in database
		try {
			DbPersonInfo testUser = DbPersonInfo.queryByEmail(uid);
			if (testUser != null) {
				return true;
			} else {
				System.out.println("Invalid email address.");
				return false;
			}
		} catch (ImException e) {
			System.out.println("ImException, cannot connect to DB");
			return false;
		}
	}

	/**
	 * Taken from http://stackoverflow.com/questions/8911356/whats-the-best-practice-to-round-a-float-to-2-decimals
	 * Round to certain number of decimals
	 *
	 * @param d
	 * @param decimalPlace
	 * @return
	*/
	private static String round(float d, int decimalPlace) {
		BigDecimal bd = new BigDecimal(Float.toString(d));
		bd = bd.setScale(decimalPlace, BigDecimal.ROUND_HALF_UP);
		return bd.toString();
	}

	private static String round(double d, int decimalPlace) {
		BigDecimal bd = new BigDecimal(Double.toString(d));
		bd = bd.setScale(decimalPlace, BigDecimal.ROUND_HALF_UP);
		return bd.toString();
    }
    
    private static String getABin(String sep,String freq,String[] shapes,boolean newVersion) {
        /*
        Grabs all the necessary values to create a line in the API output for PSD bin.
        inputs:
         - sep - user-defined separator, whether "/t" (tab) or ","
         - freq - user-defined frequency being explored in this iteration
         - shapes - shape/size combinations that fit the mass-dimension relationship for this range bin
         - newVersion - boolean used for testing when working on normalizing by mass...false in production
        output: String representing a row in the API output
        */
		HashMap<String,Double> szeMap = new HashMap<String,Double>();
        double massum = 0;
        
        //We start by creating TreeMaps containing all of the variables we want to explore in the respective HDF5 files.
        TreeMap<String,String> pMap = new TreeMap<String,String>();
        pMap.put("CIRC_ELLIPSOID_SEMI_AXIS_LENGTH","/CIRC_ELLIPSOID_SEMI_AXIS_LENGTH");
        pMap.put("EPROJ_RADIUS","/EPROJ_RADIUS");
        pMap.put("ESFC_RADIUS","/ESFC_RADIUS");
        pMap.put("EVOL_RADIUS","/EVOL_RADIUS");
        pMap.put("FRACTAL_DIM","/FRACTAL_DIM");
        pMap.put("MAX_DIM","/MAX_DIM");
        pMap.put("PROJ_AREA","/PROJ_AREA");
        pMap.put("SFC_AREA","/SFC_AREA");
        pMap.put("SDCE","/SNOW_DENSITY_CIRC_ELL");
        pMap.put("SDMD","/SNOW_DENSITY_MAX_DIM");
        pMap.put("VOL","/VOL");

        TreeMap<String,String> cMap = new TreeMap<String,String>();
        cMap.put("Q_abs","/Q_abs");
        cMap.put("Q_bk","/Q_bk");
        cMap.put("Q_ext","/Q_ext");
        cMap.put("Q_sca","/Q_sca");
        cMap.put("WAVE","/WAVE");
        cMap.put("g","/g");

		for (int d=0;d<shapes.length;d++) {
			String shape = shapes[d].split("/")[0];
            String size = shapes[d].split("/")[1];
			IHDF5Reader preader;
            preader = HDF5Factory.openForReading(serverDir+"OpenSSP/shape/"+shape+"/size/"+size+"/pblock.h5");
            //We need readers to handle both strings and floats depending on what we are pulling
			IHDF5FloatReader pfleader = preader.float32();

            Iterator<String> pSetIterator = pMap.keySet().iterator();
            //Loop through the variables and put them into szeMap (for each size, each of these should have a value)
			while(pSetIterator.hasNext()){
				String key = pSetIterator.next();
				if (key.equals("CIRC_ELLIPSOID_SEMI_AXIS_LENGTH")) {
					float[] cesal = pfleader.readArray(pMap.get(key));
					if (szeMap.containsKey("XAXIS_LENGTH")) {
						double xal = szeMap.get("XAXIS_LENGTH");
						double yal = szeMap.get("YAXIS_LENGTH");
						double zal = szeMap.get("ZAXIS_LENGTH");
						szeMap.put("XAXIS_LENGTH",xal+(double)cesal[0]);
						szeMap.put("YAXIS_LENGTH",yal+(double)cesal[1]);
						szeMap.put("ZAXIS_LENGTH",zal+(double)cesal[2]);
					} else {
						szeMap.put("XAXIS_LENGTH",(double)cesal[0]);
						szeMap.put("YAXIS_LENGTH",(double)cesal[1]);
						szeMap.put("ZAXIS_LENGTH",(double)cesal[2]);
					}
				} else if (key.equals("EVOL_RADIUS")) {
					double tval = (double)pfleader.read(pMap.get(key));
					massum = massum+(Math.pow(tval*2/1.0e6,3)/6*Math.PI*1.0e6);
					if (szeMap.containsKey(key)) {
						double val = szeMap.get(key);
						szeMap.put(key,val+(double)pfleader.read(pMap.get(key)));
					} else {
						szeMap.put(key,(double)pfleader.read(pMap.get(key)));
					}
				} else {
					if (szeMap.containsKey(key)) {
						double val = szeMap.get(key);
						szeMap.put(key,val+(double)pfleader.read(pMap.get(key)));
					} else {
						szeMap.put(key,(double)pfleader.read(pMap.get(key)));
					}
				}
			}

			preader.close();

			IHDF5Reader reader;
			reader = HDF5Factory.openForReading(serverDir+"OpenSSP/shape/"+shape+"/size/"+size+"/frequency/"+freq+"/orientation/average/cblock.h5");
			IHDF5FloatReader fleader = reader.float32();
			IHDF5StringReader streader = reader.string();

			Iterator<String> keySetIterator = cMap.keySet().iterator();
			while(keySetIterator.hasNext()){
				String key = keySetIterator.next();
				double nval = (double)fleader.read(cMap.get(key));
				if (szeMap.containsKey(key)) {
					double val = szeMap.get(key);
					szeMap.put(key,val+nval);
				} else {
					szeMap.put(key,nval);
				}
			}
			reader.close();
		}

        //We need the average mass to help scale the particle size distribution. We got the sum during the size iteration, now we divide by the number of sizes.
		double massavg = massum/shapes.length;
		String[] keyList = "MAX_DIM,SDMD,Q_bk,Q_ext,Q_sca,Q_abs,g,VOL,PROJ_AREA,SFC_AREA,EVOL_RADIUS,EPROJ_RADIUS,ESFC_RADIUS,XAXIS_LENGTH,YAXIS_LENGTH,ZAXIS_LENGTH,SDCE,FRACTAL_DIM".split(",");
        String outStr = "";
        //We are just computing arithmetic averages for all of these parameters. So sum them up, then divide by shapes.length
		for (int k=0;k<keyList.length-1;k++) {
			double summ = szeMap.get(keyList[k]);
			double av = summ/shapes.length;
			String varVal = "";
			if (av<0.001) {
				String dval = Double.toString(av);
				varVal = dval.substring(0,6)+dval.substring(dval.length()-3,dval.length());
			} else {
				varVal = String.format("%.4f",av);
			}
			outStr = outStr+varVal+sep;
		}
		double summ = szeMap.get("FRACTAL_DIM");
		double av = summ/shapes.length;
        String varVal = "";
        //Have to represent the data in different ways depending on how small the number is
		if (av<0.01) {
			String dval = Double.toString(av);
			varVal = dval.substring(0,6)+dval.substring(dval.length()-3,dval.length());
		} else {
			varVal = String.format("%.4f",av);
		}
		outStr = outStr+varVal;
		if (newVersion) {
			return outStr+"~"+massavg;
		}
		return outStr;
	}
}
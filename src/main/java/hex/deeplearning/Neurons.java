package hex.deeplearning;

import hex.FrameTask;
import hex.deeplearning.DeepLearning.Loss;
import water.Iced;
import water.MemoryManager;
import water.api.DocGen;
import water.api.Request.API;
import water.util.Utils;

import java.util.*;

/**
 * This class implements the concept of a Neuron layer in a Neural Network
 * During training, every MRTask2 F/J thread is expected to create these neurons for every map call (Cheap to make).
 * These Neurons are NOT sent over the wire.
 * The weights connecting the neurons are in a separate class (DeepLearningModel.DeepLearningModelInfo), and will be shared per node.
 */
public abstract class Neurons {
  static final int API_WEAVER = 1;
  public static DocGen.FieldDoc[] DOC_FIELDS;

  @API(help = "Number of neurons")
  protected int units;

  /**
   * Constructor of a Neuron Layer
   * @param units How many neurons are in this layer?
   */
  Neurons(int units) {
    this.units = units;
  }

  /**
   * Print the status of this neuron layer
   * @return populated String
   */
  @Override
  public String toString() {
    String s = this.getClass().getSimpleName();
    s += "\nNumber of Neurons: " + units;
    s += "\nParameters:\n" + params.toString();
    if (_dropout != null) s += "\nDropout:\n" + _dropout.toString();
    return s;
  }

  /**
   * Parameters (deep-cloned() from the user input, can be modified here, e.g. learning rate decay)
   */
  protected DeepLearning params;

  /**
   * Layer state (one per neuron): activity, error
   */
//  public transient float[] _a, _e;
  public transient Vector _a; //can be sparse for input layer
  public transient DenseVector _e;

  /**
   * References for feed-forward connectivity
   */
  public Neurons _previous; // previous layer of neurons
  DeepLearningModel.DeepLearningModelInfo _minfo; //reference to shared model info
//  public float[] _w; //reference to _minfo.weights[layer] for convenience
  public Matrix _w; //reference to _minfo.weights[layer] for convenience
//  public float[] _b; //reference to _minfo.biases[layer] for convenience
  public DenseVector _b; //reference to _minfo.biases[layer] for convenience

  // momentum
  //float[] _wm; //reference to _minfo.weights_momenta[layer] for convenience
  Matrix _wm; //reference to _minfo.weights_momenta[layer] for convenience
  //private float[] _bm; //reference to _minfo.biases_momenta[layer] for convenience
  DenseVector _bm; //reference to _minfo.biases_momenta[layer] for convenience

  // ADADELTA
  private float[] _ada;

  /**
   * For Dropout training
   */
  protected Dropout _dropout;

//  /**
//   * We need a way to encode a missing value in the neural net forward/back-propagation scheme.
//   * For simplicity and performance, we simply use the largest values to encode a missing value.
//   * If we run into exactly one of those values with regular neural net updates, then we're very
//   * likely also running into overflow problems, which will trigger a NaN somewhere, which will be
//   * caught and lead to automatic job cancellation.
//   */
//  public static final int missing_int_value = Integer.MAX_VALUE; //encode missing label or target
//  public static final double missing_double_value = Double.MAX_VALUE; //encode missing input


  /**
   * Helper to check sanity of Neuron layers
   * @param training whether training or testing is done
   */
  void sanityCheck(boolean training) {
    if (this instanceof Input) {
      assert(_previous == null);
      assert (!training || _dropout != null);
    } else {
      assert(_previous != null);
      if (_minfo.has_momenta()) {
        assert(_wm != null);
        assert(_bm != null);
        assert(_ada == null);
      }
      if (_minfo.adaDelta()) {
        if (params.rho == 0) throw new IllegalArgumentException("rho must be > 0 if epsilon is >0.");
        if (params.epsilon == 0) throw new IllegalArgumentException("epsilon must be > 0 if rho is >0.");
        assert(_minfo.adaDelta());
        assert(_ada != null);
        assert(_wm == null);
        assert(_bm == null);
      }
      if (this instanceof MaxoutDropout || this instanceof TanhDropout || this instanceof RectifierDropout) {
        assert (!training || _dropout != null);
      }
    }
  }

  /**
   * Initialization of the parameters and connectivity of a Neuron layer
   * @param neurons Array of all neuron layers, to establish feed-forward connectivity
   * @param index Which layer am I?
   * @param p User-given parameters (Job parental object hierarchy is not used)
   * @param minfo Model information (weights/biases and their momenta)
   * @param training Whether training is done or just testing (no need for dropout)
   */
  public final void init(Neurons[] neurons, int index, DeepLearning p, final DeepLearningModel.DeepLearningModelInfo minfo, boolean training) {
    params = (DeepLearning)p.clone();
    params.rate *= Math.pow(params.rate_decay, index-1);
    _a = new DenseVector(units);
    if (!(this instanceof Output) && !(this instanceof Input)) {
      _e = new DenseVector(units);
    }
    if (training && (this instanceof MaxoutDropout || this instanceof TanhDropout
            || this instanceof RectifierDropout || this instanceof Input) ) {
      _dropout = this instanceof Input ? new Dropout(units, params.input_dropout_ratio) : new Dropout(units, params.hidden_dropout_ratios[index-1]);
    }
    if (!(this instanceof Input)) {
      _previous = neurons[index-1]; //incoming neurons
      _minfo = minfo;
      _w = minfo.get_weights(index-1); //incoming weights
      _b = minfo.get_biases(index-1); //bias for this layer (starting at hidden layer)
      if (minfo.has_momenta()) {
        _wm = minfo.get_weights_momenta(index-1); //incoming weights
        _bm = minfo.get_biases_momenta(index-1); //bias for this layer (starting at hidden layer)
      }
      if (minfo.adaDelta()) {
        _ada = minfo.get_ada(index-1);
      }
    }
    sanityCheck(training);
  }

  /**
   * Forward propagation
   * @param seed For seeding the RNG inside (for dropout)
   * @param training Whether training is done or just testing (no need for dropout)
   */
  protected abstract void fprop(long seed, boolean training);

  /**
   *  Back propagation
   */
  protected abstract void bprop();

  /**
   * Backpropagation: w -= rate * dE/dw, where dE/dw = dE/dy * dy/dnet * dnet/dw
   * This method adds the dnet/dw = activation term per unit
   * @param row row index (update weights feeding to this neuron)
   * @param partial_grad partial derivative dE/dnet = dE/dy * dy/net
   * @param rate
   * @param momentum
   */
  final void bprop(final int row, final float partial_grad, final float rate, final float momentum) {
    // only correct weights if the gradient is large enough
    if (params.fast_mode || (
            // not doing fast mode, but also don't have anything else to update (neither momentum nor ADADELTA history), and no L1/L2
            !_minfo.get_params().adaptive_rate && !_minfo.has_momenta() && params.l1 == 0.0 && params.l2 == 0.0)) {
      if (partial_grad == 0) return;
    }
    if (_w instanceof DenseRowMatrix && _previous._a instanceof DenseVector)
      bprop_dense_row_dense((DenseRowMatrix)_w, (DenseRowMatrix)_wm, (DenseVector)_previous._a, _previous._e, _b, _bm, row, partial_grad, rate, momentum);
    else if (_w instanceof DenseRowMatrix && _previous._a instanceof SparseVector)
      bprop_dense_row_sparse((DenseRowMatrix)_w, (DenseRowMatrix)_wm, (SparseVector)_previous._a, _previous._e, _b, _bm, row, partial_grad, rate, momentum);
    else throw new UnsupportedOperationException("bprop for types not yet implemented.");
  }

  /**
   * Specialization of backpropagation for DenseRowMatrices and DenseVectors
   * @param _w weight matrix
   * @param _wm weight momentum matrix
   * @param prev_a activation of previous layer
   * @param prev_e error of previous layer
   * @param _b bias vector
   * @param _bm bias momentum vector
   * @param row index of the neuron for which we back-propagate
   * @param partial_grad partial derivative dE/dnet = dE/dy * dy/net
   * @param rate learning rate
   * @param momentum momentum factor (needed only if ADADELTA isn't used)
   */
  private final void bprop_dense_row_dense(final DenseRowMatrix _w, final DenseRowMatrix _wm, final DenseVector prev_a, final DenseVector prev_e,
                                           final DenseVector _b, final DenseVector _bm, final int row, final float partial_grad, float rate, final float momentum) {
    final float rho = (float)params.rho;
    final float eps = (float)params.epsilon;
    final float l1 = (float)params.l1;
    final float l2 = (float)params.l2;
    final double max_w2 = params.max_w2;
    final boolean have_momenta = _wm != null;
    final boolean have_ada = _ada != null;
    final boolean nesterov = params.nesterov_accelerated_gradient;
    final boolean update_prev = prev_e != null;
    final boolean fast_mode = params.fast_mode;
    final int cols = prev_a.size();
    final int idx = row * cols;

    for( int col = 0; col < cols; col++ ) {
      final float weight = _w.get(row,col);
      if( update_prev ) prev_e.add(col, partial_grad * weight); // propagate the error dE/dnet to the previous layer, via connecting weights
      final float previous_a = prev_a.get(col);
      if (fast_mode && previous_a == 0) continue;

      //this is the actual gradient dE/dw
      final float grad = partial_grad * previous_a - Math.signum(weight) * l1 - weight * l2;
      final int w = idx + col;

      // adaptive learning rate r from ADADELTA
      // http://www.matthewzeiler.com/pubs/googleTR2012/googleTR2012.pdf
      if (have_ada) {
        assert(!have_momenta);
        final float grad2 = grad*grad;
        _ada[2*w+1] *= rho;
        _ada[2*w+1] += (1f-rho)*grad2;
        final float RMS_dx = Utils.approxSqrt(_ada[2*w] + eps);
        final float invRMS_g = Utils.approxInvSqrt(_ada[2*w+1] + eps);
        rate = RMS_dx*invRMS_g;
        _ada[2*w] = rho * _ada[2*w] + (1f-rho)*rate*rate*grad2;
        _w.raw()[w] += rate * grad;
      } else {
        if (!nesterov) {
          final float delta = rate * grad;
          _w.raw()[w] += delta;
          if( have_momenta ) {
            _w.raw()[w] += momentum * _wm.raw()[w];
            _wm.raw()[w] = delta;
          }
        } else {
          float tmp = grad;
          if( have_momenta ) {
            _wm.raw()[w] *= momentum;
            _wm.raw()[w] += tmp;
            tmp = _wm.raw()[w];
          }
          _w.raw()[w] += rate * tmp;
        }
      }
    }
    if (max_w2 != Double.POSITIVE_INFINITY) rescale_weights(row);
    update_bias(_b, _bm, row, partial_grad, rate, momentum);
  }

  /**
   * Specialization of backpropagation for DenseRowMatrices and SparseVector for previous layer's activation and DenseVector for everything else
   * @param _w weight matrix
   * @param _wm weight momentum matrix
   * @param prev_a sparse activation of previous layer
   * @param prev_e error of previous layer
   * @param _b bias vector
   * @param _bm bias momentum vector
   * @param row index of the neuron for which we back-propagate
   * @param partial_grad partial derivative dE/dnet = dE/dy * dy/net
   * @param rate learning rate
   * @param momentum momentum factor (needed only if ADADELTA isn't used)
   */
  private final void bprop_dense_row_sparse(final DenseRowMatrix _w, final DenseRowMatrix _wm, final SparseVector prev_a, final DenseVector prev_e,
                                           final DenseVector _b, final DenseVector _bm, final int row, final float partial_grad, float rate, final float momentum) {
    final float rho = (float)params.rho;
    final float eps = (float)params.epsilon;
    final float l1 = (float)params.l1;
    final float l2 = (float)params.l2;
    final double max_w2 = params.max_w2;
    final boolean have_momenta = _wm != null;
    final boolean have_ada = _ada != null;
    final boolean nesterov = params.nesterov_accelerated_gradient;
    final boolean update_prev = prev_e != null;
    final boolean fast_mode = params.fast_mode;
    final int cols = prev_a.size();
    final int idx = row * cols;

    for (SparseVector.Iterator it=prev_a.begin(); !it.equals(prev_a.end()); it.next()) {
      final int col = it.index();
      final float weight = _w.get(row,col);
      if( update_prev ) prev_e.add(col, partial_grad * weight); // propagate the error dE/dnet to the previous layer, via connecting weights
      final float previous_a = it.value();
      assert (previous_a != 0); //only iterate over non-zeros!

      //this is the actual gradient dE/dw
      final float grad = partial_grad * previous_a - Math.signum(weight) * l1 - weight * l2;
      final int w = idx + col;

      // adaptive learning rate r from ADADELTA
      // http://www.matthewzeiler.com/pubs/googleTR2012/googleTR2012.pdf
      if (have_ada) {
        assert(!have_momenta);
        final float grad2 = grad*grad;
        _ada[2*w+1] *= rho;
        _ada[2*w+1] += (1f-rho)*grad2;
        final float RMS_dx = Utils.approxSqrt(_ada[2*w] + eps);
        final float invRMS_g = Utils.approxInvSqrt(_ada[2*w+1] + eps);
        rate = RMS_dx*invRMS_g;
        _ada[2*w] = rho * _ada[2*w] + (1f-rho)*rate*rate*grad2;
        _w.raw()[w] += rate * grad;
      } else {
        if (!nesterov) {
          final float delta = rate * grad;
          _w.raw()[w] += delta;
          if( have_momenta ) {
            _w.raw()[w] += momentum * _wm.raw()[w];
            _wm.raw()[w] = delta;
          }
        } else {
          float tmp = grad;
          if( have_momenta ) {
            _wm.raw()[w] *= momentum;
            _wm.raw()[w] += tmp;
            tmp = _wm.raw()[w];
          }
          _w.raw()[w] += rate * tmp;
        }
      }
    }
    if (max_w2 != Double.POSITIVE_INFINITY) rescale_weights(row);
    update_bias(_b, _bm, row, partial_grad, rate, momentum);
  }

  /**
   * Helper to scale down incoming weights if their squared sum exceeds a given value
   * C.f. Improving neural networks by preventing co-adaptation of feature detectors
   * @param row index of the neuron for which to scale the weights
   */
  final void rescale_weights(final int row) {
    if (_w instanceof DenseRowMatrix) {
      final int cols = _previous._a.size();
      final int idx = row * cols;
      final double max_w2 = params.max_w2;
      double r2 = Utils.sumSquares(_w.raw(), idx, idx+cols);
      if( r2 > max_w2 ) {
        final float scale = Utils.approxSqrt((float)(max_w2 / r2));
        for( int c = 0; c < cols; c++ ) _w.raw()[idx + c] *= scale;
      }
    }
    else throw new UnsupportedOperationException("not yet implemented.");
  }

  /**
   * Helper to update the bias values
   * @param _b bias vector
   * @param _bm bias momentum vector
   * @param row index of the neuron for which we back-propagate
   * @param partial_grad partial derivative dE/dnet = dE/dy * dy/net
   * @param rate learning rate
   * @param momentum momentum factor (needed only if ADADELTA isn't used)
   */
  final void update_bias(final DenseVector _b, final DenseVector _bm, final int row, final float partial_grad, final float rate, final float momentum) {
    final boolean have_momenta = _wm != null;
    if (!params.nesterov_accelerated_gradient) {
      final float delta = rate * partial_grad;
      _b.add(row, delta);
      if( have_momenta ) {
        _b.add(row, momentum * _bm.get(row));
        _bm.set(row, delta);
      }
    } else {
      float d = partial_grad;
      if( have_momenta ) {
        _bm.set(row, _bm.get(row) * momentum);
        _bm.add(row, d);
        d = _bm.get(row);
      }
      _b.add(row, rate * d);
    }
    if (Float.isInfinite(_b.get(row))) _minfo.set_unstable();
  }


  /**
   * The learning rate
   * @param n The number of training samples seen so far (for rate_annealing > 0)
   * @return Learning rate
   */
  public float rate(long n) {
    return (float)(params.rate / (1 + params.rate_annealing * n));
  }

  /**
   * The momentum - real number in [0, 1)
   * Can be a linear ramp from momentum_start to momentum_stable, over momentum_ramp training samples
   * @param n The number of training samples seen so far
   * @return momentum
   */
  public float momentum(long n) {
    double m = params.momentum_start;
    if( params.momentum_ramp > 0 ) {
      if( n >= params.momentum_ramp )
        m = params.momentum_stable;
      else
        m += (params.momentum_stable - params.momentum_start) * n / params.momentum_ramp;
    }
    return (float)m;
  }

  /**
   * Input layer of the Neural Network
   * This layer is different from other layers as it has no incoming weights,
   * but instead gets its activation values from the training points.
   */
  public static class Input extends Neurons {

    private FrameTask.DataInfo _dinfo; //training data
    SparseVector _svec;
    DenseVector _dvec;

    Input(int units, final FrameTask.DataInfo d) {
      super(units);
      _dinfo = d;
      _a = new DenseVector(units);
      _dvec = (DenseVector)_a;
    }

    @Override protected void bprop() { throw new UnsupportedOperationException(); }
    @Override protected void fprop(long seed, boolean training) { throw new UnsupportedOperationException(); }

    /**
     * One of two methods to set layer input values. This one is for raw double data, e.g. for scoring
     * @param seed For seeding the RNG inside (for input dropout)
     * @param data Data (training columns and responses) to extract the training columns
     *             from to be mapped into the input neuron layer
     */
    public void setInput(long seed, final double[] data) {
      assert(_dinfo != null);
      double [] nums = MemoryManager.malloc8d(_dinfo._nums); // a bit wasteful - reallocated each time
      int    [] cats = MemoryManager.malloc4(_dinfo._cats); // a bit wasteful - reallocated each time
      int i = 0, ncats = 0;
      for(; i < _dinfo._cats; ++i){
        int c = (int)data[i];
        if(c != 0)cats[ncats++] = c + _dinfo._catOffsets[i] - 1;
      }
      final int n = data.length; // data contains only input features - no response is included
      for(;i < n;++i){
        double d = data[i];
        if(_dinfo._normMul != null) d = (d - _dinfo._normSub[i-_dinfo._cats])*_dinfo._normMul[i-_dinfo._cats];
        nums[i-_dinfo._cats] = d;
      }
      setInput(seed, nums, ncats, cats);
    }

    /**
     * The second method used to set input layer values. This one is used directly by FrameTask.processRow() and by the method above.
     * @param seed For seeding the RNG inside (for input dropout)
     * @param nums Array containing numerical values, can be NaN
     * @param numcat Number of horizontalized categorical non-zero values (i.e., those not being the first factor of a class)
     * @param cats Array of indices, the first numcat values are the input layer unit (==column) indices for the non-zero categorical values
     *             (This allows this array to be re-usable by the caller, without re-allocating each time)
     */
    public void setInput(long seed, final double[] nums, final int numcat, final int[] cats) {
      _a = _dvec;
      for (int i=0; i<numcat; ++i) _a.set(cats[i], 1f);
      for (int i=0; i<nums.length; ++i) _a.set(_dinfo.numStart() + i, Double.isNaN(nums[i]) ? 0f : (float) nums[i]);

      // Input Dropout
      if (_dropout == null) return;
      seed += params.seed + 0x1337B4BE;
      _dropout.randomlySparsifyActivation(_a.raw(), seed);
// FIXME: HACK TO ALWAYS BE SPARSE
//      _svec = new SparseVector(_dvec);
//      assert(_svec instanceof SparseVector);
//      _a = _svec;
//      assert(_a instanceof SparseVector);
    }

  }

  /**
   * Tanh neurons - most common, most stable
   */
  public static class Tanh extends Neurons {
    public Tanh(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      if (_previous instanceof Input && _previous._a instanceof SparseVector)
        gemv_naive((DenseVector)_a, (DenseRowMatrix)_w, (SparseVector)_previous._a, _b, _dropout != null ? _dropout.bits() : null);
      else
        gemv((DenseVector)_a, (DenseRowMatrix)_w, (DenseVector)_previous._a, _b, _dropout != null ? _dropout.bits() : null);

      for( int o = 0; o < _a.size(); o++ ) {
        _a.set(o, 1f - 2f / (1f + (float)Math.exp(2*_a.get(o)))); //evals faster than tanh(x), but is slightly less numerically stable - OK
//        _a[o] = (float)(1 - 2 / (1 + Utils.approxExp(2d*_a[o]))); //even faster, but ~ 4% relative error - not worth it here
      }
    }
    @Override protected void bprop() {
      final long processed = _minfo.get_processed_total();
      float m = momentum(processed);
      float r = rate(processed) * (1 - m);
      for( int u = 0; u < _a.size(); u++ ) {
        // Computing partial derivative g = dE/dnet = dE/dy * dy/dnet, where dE/dy is the backpropagated error
        // dy/dnet = (1 - a^2) for y(net) = tanh(net)
        float g = _e.get(u) * (1f - _a.get(u) * _a.get(u));
        bprop(u, g, r, m);
      }
    }
  }

  /**
   * Tanh neurons with dropout
   */
  public static class TanhDropout extends Tanh {
    public TanhDropout(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      if (training) {
        seed += params.seed + 0xDA7A6000;
        _dropout.fillBytes(seed);
        super.fprop(seed, true);
      }
      else {
        super.fprop(seed, false);
        Utils.div(_a.raw(), 2.f);
      }
    }
  }

  /**
   * Maxout neurons
   */
  public static class Maxout extends Neurons {
    public Maxout(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      float max = 0;
      if (_previous._a instanceof DenseVector) {
        for( int o = 0; o < _a.size(); o++ ) {
          _a.set(o, 0);
          if( !training || _dropout == null || _dropout.unit_active(o) ) {
            _a.set(o, Float.NEGATIVE_INFINITY);
            for( int i = 0; i < _previous._a.size(); i++ )
              _a.set(o, Math.max(_a.get(o), _w.get(o, i) * _previous._a.get(i)));
            if (Float.isInfinite(-_a.get(o))) _a.set(o, 0); //catch the case where there is dropout (and/or input sparsity) -> no max found!
            _a.add(o, _b.get(o));
            max = Math.max(_a.get(o), max);
          }
        }
        if( max > 1 ) Utils.div(_a.raw(), max);
      }
      else {
        SparseVector x = (SparseVector)_previous._a;
        for( int o = 0; o < _a.size(); o++ ) {
          _a.set(o, 0);
          if( !training || _dropout == null || _dropout.unit_active(o) ) {
//            _a.set(o, Float.NEGATIVE_INFINITY);
//            for( int i = 0; i < _previous._a.size(); i++ )
//              _a.set(o, Math.max(_a.get(o), _w.get(o, i) * _previous._a.get(i)));
            float mymax = Float.NEGATIVE_INFINITY;
            for (SparseVector.Iterator it=x.begin(); !it.equals(x.end()); it.next()) {
              mymax = Math.max(mymax, _w.get(o, it.index()) * it.value());
            }
            _a.set(o, mymax);
            if (Float.isInfinite(-_a.get(o))) _a.set(o, 0); //catch the case where there is dropout (and/or input sparsity) -> no max found!
            _a.add(o, _b.get(o));
            max = Math.max(_a.get(o), max);
          }
        }
        if( max > 1 ) Utils.div(_a.raw(), max);
      }
    }
    @Override protected void bprop() {
      final long processed = _minfo.get_processed_total();
      float m = momentum(processed);
      float r = rate(processed) * (1 - m);
      for( int u = 0; u < _a.size(); u++ ) {
        float g = _e.get(u);
//                if( _a[o] < 0 )   Not sure if we should be using maxout with a hard zero bottom
//                    g = 0;
        bprop(u, g, r, m);
      }
    }
  }

  /**
   * Maxout neurons with dropout
   */
  public static class MaxoutDropout extends Maxout {
    public MaxoutDropout(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      if (training) {
        seed += params.seed + 0x51C8D00D;
        _dropout.fillBytes(seed);
        super.fprop(seed, true);
      }
      else {
        super.fprop(seed, false);
        Utils.div(_a.raw(), 2.f);
      }
    }
  }

  /**
   * Rectifier linear unit (ReLU) neurons
   */
  public static class Rectifier extends Neurons {
    public Rectifier(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      if (_previous instanceof Input && _previous._a instanceof SparseVector)
        gemv_naive((DenseVector)_a, (DenseRowMatrix)_w, (SparseVector)_previous._a, _b, _dropout != null ? _dropout.bits() : null);
      else
        gemv((DenseVector)_a, (DenseRowMatrix)_w, (DenseVector)_previous._a, _b, _dropout != null ? _dropout.bits() : null);

      for( int o = 0; o < _a.size(); o++ ) {
        _a.set(o, Math.max(_a.get(o), 0f));
      }
    }

    @Override protected void bprop() {
      final long processed = _minfo.get_processed_total();
      float m = momentum(processed);
      float r = rate(processed) * (1 - m);
      for( int u = 0; u < _a.size(); u++ ) {
        //(d/dx)(max(0,x)) = 1 if x > 0, otherwise 0
        final float g = _a.get(u) > 0f ? _e.get(u) : 0;
        bprop(u, g, r, m);
      }
    }
  }

  /**
   * Rectifier linear unit (ReLU) neurons with dropout
   */
  public static class RectifierDropout extends Rectifier {
    public RectifierDropout(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      if (training) {
        seed += params.seed + 0x3C71F1ED;
        _dropout.fillBytes(seed);
        super.fprop(seed, true);
      }
      else {
        super.fprop(seed, false);
        Utils.div(_a.raw(), 2.f);
      }
    }
  }

  /**
   * Abstract class for Output neurons
   */
  public static abstract class Output extends Neurons {
    static final int API_WEAVER = 1;
    public static DocGen.FieldDoc[] DOC_FIELDS;
    Output(int units) { super(units); }
    protected abstract void fprop(); //don't differentiate between testing/training
    protected void fprop(long seed, boolean training) { throw new UnsupportedOperationException(); }
    protected void bprop() { throw new UnsupportedOperationException(); }
  }

  /**
   * Output neurons for classification - Softmax
   */
  public static class Softmax extends Output {
    public Softmax(int units) { super(units); }
    @Override protected void fprop() {
      gemv_row_optimized(_a.raw(), _w.raw(), _previous._a.raw(), _b.raw(), null);
      final float max = Utils.maxValue(_a.raw());
      float scale = 0;
      for( int o = 0; o < _a.size(); o++ ) {
        _a.set(o, (float)Math.exp(_a.get(o) - max));
        scale += _a.get(o);
      }
      for( int o = 0; o < _a.size(); o++ ) {
        if (Float.isNaN(_a.get(o)))
          throw new RuntimeException("Numerical instability, predicted NaN.");
        _a.raw()[o] /= scale;
      }
    }

    /**
     * Backpropagation for classification
     * Update every weight as follows: w += -rate * dE/dw
     * Compute dE/dw via chain rule: dE/dw = dE/dy * dy/dnet * dnet/dw, where net = sum(xi*wi)+b and y = activation function
     * @param target actual class label
     */
    protected void bprop(int target) {
//      if (target == missing_int_value) return; //ignore missing response values
      final long processed = _minfo.get_processed_total();
      float m = momentum(processed);
      float r = rate(processed) * (1 - m);
      float g; //partial derivative dE/dy * dy/dnet
      for( int u = 0; u < _a.size(); u++ ) {
        final float t = (u == target ? 1 : 0);
        final float y = _a.get(u);
        //dy/dnet = derivative of softmax = (1-y)*y
        if (params.loss == Loss.CrossEntropy) {
          //nothing else needed, -dCE/dy * dy/dnet = target - y
          //cf. http://www.stanford.edu/group/pdplab/pdphandbook/handbookch6.html
          g = t - y;
        } else {
          assert(params.loss == Loss.MeanSquare);
          //-dMSE/dy = target-y
          g = (t - y) * (1 - y) * y;
        }
        // this call expects dE/dnet
        bprop(u, g, r, m);
      }
    }
  }

  /**
   * Output neurons for regression - Softmax
   */
  public static class Linear extends Output {
    public Linear(int units) { super(units); }
    @Override protected void fprop() {
      if (_w instanceof DenseRowMatrix)
        gemv_row_optimized(_a.raw(), _w.raw(), _previous._a.raw(), _b.raw(), null);
      else throw new UnsupportedOperationException("only row matrix");
    }

    /**
     * Backpropagation for regression
     * @param target floating-point target value
     */
    protected void bprop(float target) {
//      if (target == missing_double_value) return;
      if (params.loss != Loss.MeanSquare) throw new UnsupportedOperationException("Regression is only implemented for MeanSquare error.");
      final int u = 0;
      // Computing partial derivative: dE/dnet = dE/dy * dy/dnet = dE/dy * 1
      final float g = target - _a.get(u); //for MSE -dMSE/dy = target-y
      final long processed = _minfo.get_processed_total();
      float m = momentum(processed);
      float r = rate(processed) * (1 - m);
      bprop(u, g, r, m);
    }
  }

  /**
   * Mat-Vec Plus Add (with optional row dropout)
   * @param res = a*x+y (pre-allocated, will be overwritten)
   * @param a matrix of size rows x cols
   * @param x vector of length cols
   * @param y vector of length rows
   * @param row_bits if not null, check bits of this byte[] to determine whether a row is used or not
   */
  static void gemv_naive(final float[] res, final float[] a, final float[] x, final float[] y, byte[] row_bits) {
    final int cols = x.length;
    final int rows = y.length;
    assert(res.length == rows);
    for(int row = 0; row<rows; row++) {
      res[row] = 0;
      if( row_bits != null && (row_bits[row / 8] & (1 << (row % 8))) == 0) continue;
      for(int col = 0; col<cols; col++)
        res[row] += a[row*cols+col] * x[col];
      res[row] += y[row];
    }
  }

  /**
   * Optimized Mat-Vec Plus Add (with optional row dropout)
   * Optimization: Partial sums can be evaluated in parallel
   * @param res = a*x+y (pre-allocated, will be overwritten)
   * @param a matrix of size rows x cols
   * @param x vector of length cols
   * @param y vector of length rows
   * @param row_bits if not null, check bits of this byte[] to determine whether a row is used or not
   */
  static void gemv_row_optimized(float[] res, float[] a, float[] x, final float[] y, byte[] row_bits) {
    final int cols = x.length;
    final int rows = y.length;
    assert(res.length == rows);
    final int extra=cols-cols%8;
    final int multiple = (cols/8)*8-1;
    int idx = 0;
    for (int row = 0; row<rows; row++) {
      res[row] = 0;
      if( row_bits == null || (row_bits[row / 8] & (1 << (row % 8))) != 0) {
        float psum0 = 0, psum1 = 0, psum2 = 0, psum3 = 0, psum4 = 0, psum5 = 0, psum6 = 0, psum7 = 0;
        for (int col = 0; col < multiple; col += 8) {
          int off = idx + col;
          psum0 += a[off    ] * x[col    ];
          psum1 += a[off + 1] * x[col + 1];
          psum2 += a[off + 2] * x[col + 2];
          psum3 += a[off + 3] * x[col + 3];
          psum4 += a[off + 4] * x[col + 4];
          psum5 += a[off + 5] * x[col + 5];
          psum6 += a[off + 6] * x[col + 6];
          psum7 += a[off + 7] * x[col + 7];
        }
        res[row] += psum0 + psum1 + psum2 + psum3;
        res[row] += psum4 + psum5 + psum6 + psum7;
        for (int col = extra; col < cols; col++)
          res[row] += a[idx + col] * x[col];
        res[row] += y[row];
      }
      idx += cols;
    }
  }

  static void gemv(final DenseVector res, final DenseRowMatrix a, final DenseVector x, final DenseVector y, byte[] row_bits) {
    gemv_row_optimized(res.raw(), a.raw(), x.raw(), y.raw(), row_bits);
  }

  static void gemv_naive(final DenseVector res, final DenseRowMatrix a, final DenseVector x, final DenseVector y, byte[] row_bits) {
    gemv_naive(res.raw(), a.raw(), x.raw(), y.raw(), row_bits);
  }

  //TODO: make optimized version for col matrix
  static void gemv_naive(final DenseVector res, final DenseColMatrix a, final DenseVector x, final DenseVector y, byte[] row_bits) {
    final int cols = x.size();
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
    }
    for(int c = 0; c<cols; c++) {
      final float val = x.get(c);
      for(int r = 0; r<rows; r++) {
        if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
        res.add(r, a.get(r,c) * val);
      }
    }
    for(int r = 0; r<rows; r++) {
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      res.add(r, y.get(r));
    }
  }

  static void gemv_naive(final DenseVector res, final DenseRowMatrix a, final SparseVector x, final DenseVector y, byte[] row_bits) {
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      for (SparseVector.Iterator it=x.begin(); !it.equals(x.end()); it.next())
        res.add(r, a.get(r,it.index()) * it.value());
      res.add(r, y.get(r));
    }
  }

  static void gemv_naive(final DenseVector res, final DenseColMatrix a, final SparseVector x, final DenseVector y, byte[] row_bits) {
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
    }
    for (SparseVector.Iterator it=x.begin(); !it.equals(x.end()); it.next()) {
      final float val = it.value();
      if (val == 0f) continue;
      for(int r = 0; r<rows; r++) {
        if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
        res.add(r, a.get(r,it.index()) * val);
      }
    }
    for(int r = 0; r<rows; r++) {
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      res.add(r, y.get(r));
    }
  }

  static void gemv_naive(final DenseVector res, final SparseRowMatrix a, final SparseVector x, final DenseVector y, byte[] row_bits) {
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      // iterate over all non-empty columns for this row
      TreeMap<Integer, Float> row = a.row(r);
      Set<Map.Entry<Integer,Float>> set = row.entrySet();

      Iterator<Map.Entry<Integer,Float>> itA = set.iterator();
      SparseVector.Iterator itB=x.begin();
//      while(itA.hasNext() && itB.hasNext()) {
//      }

      for (Map.Entry<Integer,Float> e : set) {
        final float val = x.get(e.getKey());
        if (val != 0f) res.add(r, e.getValue() * val); //TODO: iterate over both iterators and only add where there are matching indices
      }
      res.add(r, y.get(r));
    }
  }

  static void gemv_naive(final DenseVector res, final SparseColMatrix a, final SparseVector x, final DenseVector y, byte[] row_bits) {
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
    }
    for(int c = 0; c<a.cols(); c++) {
      TreeMap<Integer, Float> col = a.col(c);
      final float val = x.get(c);
      if (val == 0f) continue;
      for (Map.Entry<Integer,Float> e : col.entrySet()) {
        final int r = e.getKey();
        if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
        // iterate over all non-empty columns for this row
        res.add(r, e.getValue() * val);
      }
    }
    for(int r = 0; r<rows; r++) {
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      res.add(r, y.get(r));
    }
  }

  /**
   * Abstract vector interface
   */
  public abstract interface Vector {
    public abstract float get(int i);
    public abstract void set(int i, float val);
    public abstract void add(int i, float val);
    public abstract int size();
    public abstract float[] raw();
  }

  /**
   * Dense vector implementation
   */
  public static class DenseVector extends Iced implements Vector {
    private float[] _data;
    DenseVector(int len) { _data = new float[len]; }
    DenseVector(float[] v) { _data = v; }
    @Override public float get(int i) { return _data[i]; }
    @Override public void set(int i, float val) { _data[i] = val; }
    @Override public void add(int i, float val) { _data[i] += val; }
    @Override public int size() { return _data.length; }
    @Override public float[] raw() { return _data; }
  }

  /**
   * Sparse vector implementation
   */
  public static class SparseVector extends Iced implements Vector {
    private int[] _indices;
    private float[] _values;
    private int _size;
    private int _nnz;

    @Override public int size() { return _size; }
    public int nnz() { return _nnz; }

    SparseVector(float[] v) { this(new DenseVector(v)); }
    SparseVector(final DenseVector dv) {
      _size = dv.size();
      // first count non-zeros
      for (int i=0; i<dv._data.length; ++i) {
        if (dv.get(i) != 0.0f) {
          _nnz++;
        }
      }
      // only allocate what's needed
      _indices = new int[_nnz];
      _values = new float[_nnz];
      // fill values
      int idx = 0;
      for (int i=0; i<dv._data.length; ++i) {
        if (dv.get(i) != 0.0f) {
          _indices[idx] = i;
          _values[idx] = dv.get(i);
          idx++;
        }
      }
      assert(idx == nnz());
    }

    /**
     * Slow path access to i-th element
     * @param i
     * @return
     */
    @Override public float get(int i) {
      final int idx = Arrays.binarySearch(_indices, i);
      return idx < 0 ? 0f : _values[idx];
    }

    @Override
    public void set(int i, float val) {
      throw new UnsupportedOperationException("setting values in a sparse vector is not implemented.");
    }

    @Override
    public void add(int i, float val) {
      throw new UnsupportedOperationException("adding values in a sparse vector is not implemented.");
    }

    @Override
    public float[] raw() {
      throw new UnsupportedOperationException("raw access to the data in a sparse vector is not implemented.");
    }

    /**
     * Iterator over a sparse vector
     */
    public class Iterator {
      int _idx; //which nnz
      Iterator(int id) { _idx = id; }
      Iterator next() {
        _idx++;
        return this;
      }
      boolean hasNext() {
        return _idx < _indices.length-1;
      }
      boolean equals(Iterator other) {
        return _idx == other._idx;
      }
      @Override
      public String toString() {
        return index() + " -> " + value();
      }
      float value() { return _values[_idx]; }
      int index() { return _indices[_idx]; }
    }

    public Iterator begin() { return new Iterator(0); }
    public Iterator end() { return new Iterator(_indices.length); }
  }

  /**
   * Abstract matrix interface
   */
  public abstract interface Matrix {
    abstract float get(int row, int col);
    abstract void set(int row, int col, float val);
    abstract void add(int row, int col, float val);
    abstract int cols();
    abstract int rows();
    abstract long size();
    abstract float[] raw();
  }

  /**
   * Dense row matrix implementation
   */
  public final static class DenseRowMatrix extends Iced implements Matrix {
    private float[] _data;
    private int _cols;
    private int _rows;
    DenseRowMatrix(int rows, int cols) { this(new float[cols*rows], rows, cols); }
    DenseRowMatrix(float[] v, int rows, int cols) { _data = v; _rows = rows; _cols = cols; }
    @Override public float get(int row, int col) { assert(row<_rows && col<_cols); return _data[row*_cols + col]; }
    @Override public void set(int row, int col, float val) { assert(row<_rows && col<_cols); _data[row*_cols + col] = val; }
    @Override public void add(int row, int col, float val) { assert(row<_rows && col<_cols); _data[row*_cols + col] += val; }
    @Override public int cols() { return _cols; }
    @Override public int rows() { return _rows; }
    @Override public long size() { return (long)_rows*(long)_cols; }
    public float[] raw() { return _data; }
  }

  /**
   * Dense column matrix implementation
   */
  public final static class DenseColMatrix extends Iced implements Matrix {
    private float[] _data;
    private int _cols;
    private int _rows;
    DenseColMatrix(int rows, int cols) { this(new float[cols*rows], rows, cols); }
    DenseColMatrix(float[] v, int rows, int cols) { _data = v; _rows = rows; _cols = cols; }
    DenseColMatrix(DenseRowMatrix m, int rows, int cols) { this(rows, cols); for (int row=0;row<rows;++row) for (int col=0;col<cols;++col) set(row,col, m.get(row,col)); }
    @Override public float get(int row, int col) { assert(row<_rows && col<_cols); return _data[col*_rows + row]; }
    @Override public void set(int row, int col, float val) { assert(row<_rows && col<_cols); _data[col*_rows + row] = val; }
    @Override public void add(int row, int col, float val) { assert(row<_rows && col<_cols); _data[col*_rows + row] += val; }
    @Override public int cols() { return _cols; }
    @Override public int rows() { return _rows; }
    @Override public long size() { return (long)_rows*(long)_cols; }
    public float[] raw() { return _data; }
  }

  /**
   * Sparse row matrix implementation
   */
  public static class SparseRowMatrix implements Matrix {
    private TreeMap<Integer, Float>[] _rows;
    private int _cols;
    SparseRowMatrix(int rows, int cols) { this(null, rows, cols); }
    SparseRowMatrix(Matrix v, int rows, int cols) {
      _rows = new TreeMap[rows];
      for (int row=0;row<rows;++row) _rows[row] = new TreeMap<Integer, Float>();
      _cols = cols;
      if (v!=null)
        for (int row=0;row<rows;++row)
          for (int col=0;col<cols;++col)
            if (v.get(row,col) != 0f)
              add(row,col, v.get(row,col));
    }
    @Override public float get(int row, int col) { Float v = _rows[row].get(col); if (v == null) return 0f; else return v; }
    @Override public void add(int row, int col, float val) { set(row,col,get(row,col)+val); }
    @Override public void set(int row, int col, float val) { _rows[row].put(col, val); }
    @Override public int cols() { return _cols; }
    @Override public int rows() { return _rows.length; }
    @Override public long size() { return (long)_rows.length*(long)_cols; }
    TreeMap<Integer, Float> row(int row) { return _rows[row]; }
    public float[] raw() { throw new UnsupportedOperationException("raw access to the data in a sparse matrix is not implemented."); }
  }

  /**
   * Sparse column matrix implementation
   */
  static class SparseColMatrix implements Matrix {
    private TreeMap<Integer, Float>[] _cols;
    private int _rows;
    SparseColMatrix(int rows, int cols) { this(null, rows, cols); }
    SparseColMatrix(Matrix v, int rows, int cols) {
      _rows = rows;
      _cols = new TreeMap[cols];
      for (int col=0;col<cols;++col) _cols[col] = new TreeMap<Integer, Float>();
      if (v!=null)
        for (int row=0;row<rows;++row)
          for (int col=0;col<cols;++col)
            if (v.get(row,col) != 0f)
              add(row,col, v.get(row,col));
    }
    @Override public float get(int row, int col) { Float v = _cols[col].get(row); if (v == null) return 0f; else return v; }
    @Override public void add(int row, int col, float val) { set(row,col,get(row,col)+val); }
    @Override public void set(int row, int col, float val) { _cols[col].put(row, val); }
    @Override public int cols() { return _cols.length; }
    @Override public int rows() { return _rows; }
    @Override public long size() { return (long)_rows*(long)_cols.length; }
    TreeMap<Integer, Float> col(int col) { return _cols[col]; }
    public float[] raw() { throw new UnsupportedOperationException("raw access to the data in a sparse matrix is not implemented."); }
  }
}